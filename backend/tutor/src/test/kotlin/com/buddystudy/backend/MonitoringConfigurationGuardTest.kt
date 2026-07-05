package com.buddystudy.backend

import com.buddystudy.backend.config.MonitoringConfigurationGuard
import com.buddystudy.backend.config.PropertiesConfig
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

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
    fun `production profile matching is case insensitive for monitoring guard`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=Production",
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
    fun `prod scheduler can start when scheduler monitoring dependencies are configured`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=https://hooks.slack.test/scheduler",
                "buddystudy.monitoring.admin-base-url=https://api.ghkdqhrbals.org/admin",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
            }
    }

    @Test
    fun `prod scheduler fails fast when admin run url is missing`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=https://hooks.slack.test/scheduler",
                "buddystudy.monitoring.admin-base-url=",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "MONITORING_ADMIN_BASE_URL must be an HTTPS URL in prod.",
                )
            }
    }

    @Test
    fun `prod scheduler fails fast when admin run url is not https`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=https://hooks.slack.test/scheduler",
                "buddystudy.monitoring.admin-base-url=http://api.ghkdqhrbals.org/admin",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "MONITORING_ADMIN_BASE_URL must be an HTTPS URL in prod.",
                )
            }
    }

    @Test
    fun `prod scheduler fails fast when scheduler readiness is disabled`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=https://hooks.slack.test/scheduler",
                "buddystudy.monitoring.admin-base-url=https://api.ghkdqhrbals.org/admin",
                "buddystudy.monitoring.scheduler-readiness-enabled=false",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "Scheduler readiness monitoring must be enabled in prod when scheduler is enabled.",
                )
            }
    }

    @Test
    fun `prod scheduler fails fast when no scheduler jobs are monitored`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=https://hooks.slack.test/scheduler",
                "buddystudy.monitoring.admin-base-url=https://api.ghkdqhrbals.org/admin",
                "buddystudy.monitoring.scheduler-monitored-jobs=",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "At least one scheduler job must be monitored in prod when scheduler is enabled.",
                )
            }
    }

    @Test
    fun `prod scheduler allows managed jobs that are intentionally excluded from readiness monitoring`() {
        contextRunner
            .withBean("questionScheduleJob", ManagedJob::class.java, Supplier { fakeJob("question-schedule") })
            .withBean("adminCorrectionJob", ManagedJob::class.java, Supplier { fakeJob("admin-analytics-correction") })
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=https://hooks.slack.test/scheduler",
                "buddystudy.monitoring.admin-base-url=https://api.ghkdqhrbals.org/admin",
                "buddystudy.monitoring.scheduler-monitored-jobs=question-schedule",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
            }
    }

    @Test
    fun `prod scheduler fails fast when monitored job name is unknown`() {
        contextRunner
            .withBean("questionScheduleJob", ManagedJob::class.java, Supplier { fakeJob("question-schedule") })
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=https://hooks.slack.test/scheduler",
                "buddystudy.monitoring.admin-base-url=https://api.ghkdqhrbals.org/admin",
                "buddystudy.monitoring.scheduler-monitored-jobs=question-schedule,question-schedul",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "Prod scheduler monitoring includes unknown jobs: question-schedul.",
                )
            }
    }

    @Test
    fun `prod scheduler fails fast when Slack timeout is outside supported bounds`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=https://hooks.slack.test/scheduler",
                "buddystudy.monitoring.admin-base-url=https://api.ghkdqhrbals.org/admin",
                "buddystudy.monitoring.slack-timeout-ms=999999",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "MONITORING_SLACK_TIMEOUT_MS must be between 1000 and 25000 in prod.",
                )
            }
    }

    @Test
    fun `prod scheduler fails fast when scheduler stale threshold is outside supported bounds`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=https://hooks.slack.test/scheduler",
                "buddystudy.monitoring.admin-base-url=https://api.ghkdqhrbals.org/admin",
                "buddystudy.monitoring.scheduler-stale-threshold-minutes=120",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "MONITORING_SCHEDULER_STALE_THRESHOLD_MINUTES must be between 1 and 60 in prod.",
                )
            }
    }

    @Test
    fun `prod scheduler fails fast when scheduler startup grace is outside supported bounds`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.slack-webhook-url=https://hooks.slack.test/scheduler",
                "buddystudy.monitoring.admin-base-url=https://api.ghkdqhrbals.org/admin",
                "buddystudy.monitoring.scheduler-startup-grace-minutes=120",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "MONITORING_SCHEDULER_STARTUP_GRACE_MINUTES must be between 0 and 60 in prod.",
                )
            }
    }

    private fun fakeJob(jobName: String): ManagedJob =
        object : ManagedJob {
            override val name: String = jobName
            override fun run(): String = "ok"
        }
}
