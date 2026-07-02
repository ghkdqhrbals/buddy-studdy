package com.buddystudy.backend

import com.buddystudy.backend.config.MonitoringConfigurationGuard
import com.buddystudy.backend.config.PropertiesConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MonitoringConfigurationGuardTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java, MonitoringConfigurationGuard::class.java)

    @Test
    fun `prod scheduler fails fast when scheduler Slack webhook is missing`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "SLACK_WEBHOOK_URL is required when prod scheduler monitoring is enabled.",
                )
            }
    }

    @Test
    fun `dev scheduler can start without scheduler Slack webhook`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=dev",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
            }
    }

    @Test
    fun `prod scheduler can start when scheduler Slack webhook is configured`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=https://hooks.slack.test/scheduler",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
            }
    }
}
