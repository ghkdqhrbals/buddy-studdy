package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

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
    fun `dev scheduler can start without external monitoring configuration`(): Unit = runBlocking {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=dev",
                "buddystudy.scheduler.enabled=true",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
            }
    }

    @Test
    fun `production profile matching is case insensitive for monitoring guard`(): Unit = runBlocking {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=Production",
                "buddystudy.scheduler.enabled=true",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
            }
    }

    @Test
    fun `prod scheduler fails fast when scheduler readiness is disabled`(): Unit = runBlocking {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
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
    fun `prod scheduler fails fast when no scheduler jobs are monitored`(): Unit = runBlocking {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
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
    fun `prod scheduler allows managed jobs that are intentionally excluded from readiness monitoring`(): Unit = runBlocking {
        contextRunner
            .withBean("questionScheduleJob", ManagedJob::class.java, Supplier { fakeJob("question-schedule") })
            .withBean("maintenanceJob", ManagedJob::class.java, Supplier { fakeJob("maintenance-cleanup") })
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
                "buddystudy.monitoring.scheduler-monitored-jobs=question-schedule",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
            }
    }

    @Test
    fun `prod scheduler fails fast when monitored job name is unknown`(): Unit = runBlocking {
        contextRunner
            .withBean("questionScheduleJob", ManagedJob::class.java, Supplier { fakeJob("question-schedule") })
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
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
    fun `prod scheduler fails fast when scheduler stale threshold is outside supported bounds`(): Unit = runBlocking {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
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
    fun `prod scheduler fails fast when scheduler startup grace is outside supported bounds`(): Unit = runBlocking {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "buddystudy.scheduler.enabled=true",
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
            override suspend fun run(): String = "ok"
        }
}
