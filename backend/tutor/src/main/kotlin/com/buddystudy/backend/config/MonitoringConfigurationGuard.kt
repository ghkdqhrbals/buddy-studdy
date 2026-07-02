package com.buddystudy.backend.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class MonitoringConfigurationGuard(
    private val properties: BuddyStudyProperties,
    private val environment: Environment,
) : InitializingBean {
    override fun afterPropertiesSet() {
        if (!isProdProfile() || !properties.scheduler.enabled) {
            return
        }
        if (properties.monitoring.slackWebhookUrl.isBlank()) {
            error("SLACK_WEBHOOK_URL is required when prod scheduler monitoring is enabled.")
        }
    }

    private fun isProdProfile(): Boolean =
        environment.activeProfiles.any { it == "prod" || it == "production" }
}
