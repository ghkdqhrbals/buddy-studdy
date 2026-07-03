package com.buddystudy.backend.config

import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

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
        if (!properties.monitoring.schedulerReadinessEnabled) {
            error("Scheduler readiness monitoring must be enabled in prod when scheduler is enabled.")
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
    }

    private fun isProdProfile(): Boolean =
        environment.activeProfiles.any { profile ->
            val normalized = profile.trim()
            normalized.equals("prod", ignoreCase = true) ||
                normalized.equals("production", ignoreCase = true)
        }
}
