package com.buddystudy.backend.config

import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URI

@Component
class MonitoringConfigurationGuard(
    private val properties: BuddyStudyProperties,
    private val environment: Environment,
    private val managedJobs: List<ManagedJob>,
) : InitializingBean {
    override fun afterPropertiesSet() {
        if (!isProdProfile() || !properties.scheduler.enabled) {
            return
        }
        if (properties.monitoring.slackWebhookUrl.isBlank()) {
            error("SLACK_WEBHOOK_URL is required when prod scheduler monitoring is enabled.")
        }
        if (!isHttpsUrl(properties.monitoring.adminBaseUrl)) {
            error("MONITORING_ADMIN_BASE_URL must be an HTTPS URL in prod.")
        }
        if (!properties.monitoring.schedulerReadinessEnabled) {
            error("Scheduler readiness monitoring must be enabled in prod when scheduler is enabled.")
        }
        if (!properties.monitoring.coordinatorReadinessEnabled) {
            error("Redis Stream Coordinator readiness monitoring must be enabled in prod when scheduler is enabled.")
        }
        if (!isHttpUrl(properties.monitoring.coordinatorBaseUrl)) {
            error("MONITORING_COORDINATOR_BASE_URL must be an HTTP or HTTPS URL in prod.")
        }
        if (properties.monitoring.slackTimeoutMs !in 1_000..25_000) {
            error("MONITORING_SLACK_TIMEOUT_MS must be between 1000 and 25000 in prod.")
        }
        if (properties.monitoring.schedulerStaleThresholdMinutes !in 1..60) {
            error("MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES must be between 1 and 60 in prod.")
        }
        if (properties.monitoring.schedulerStartupGraceMinutes !in 0..60) {
            error("MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES must be between 0 and 60 in prod.")
        }
        if (properties.monitoring.schedulerMonitoredJobs.none { it.isNotBlank() }) {
            error("At least one scheduler job must be monitored in prod when scheduler is enabled.")
        }
        val monitoredJobs = properties.monitoring.schedulerMonitoredJobs
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val missingManagedJobs = managedJobs
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .filterNot { it in monitoredJobs }
        if (missingManagedJobs.isNotEmpty()) {
            error("Prod scheduler monitoring is missing managed jobs: ${missingManagedJobs.joinToString(", ")}.")
        }
        val managedJobNames = managedJobs
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val unknownMonitoredJobs = if (managedJobNames.isEmpty()) {
            emptyList()
        } else {
            monitoredJobs.filterNot { it in managedJobNames }
        }
        if (unknownMonitoredJobs.isNotEmpty()) {
            error("Prod scheduler monitoring includes unknown jobs: ${unknownMonitoredJobs.joinToString(", ")}.")
        }
    }

    private fun isProdProfile(): Boolean =
        environment.activeProfiles.any { profile ->
            val normalized = profile.trim()
            normalized.equals("prod", ignoreCase = true) ||
                normalized.equals("production", ignoreCase = true)
        }

    private fun isHttpsUrl(value: String): Boolean =
        runCatching {
            val uri = URI(value.trim())
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        }.getOrDefault(false)

    private fun isHttpUrl(value: String): Boolean =
        runCatching {
            val uri = URI(value.trim())
            val scheme = uri.scheme.orEmpty()
            (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
        }.getOrDefault(false)
}
