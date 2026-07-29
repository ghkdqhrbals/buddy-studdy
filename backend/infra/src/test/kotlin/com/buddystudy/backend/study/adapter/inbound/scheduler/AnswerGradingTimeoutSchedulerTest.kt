package com.buddystudy.backend.study.adapter.inbound.scheduler

import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.study.application.port.inbound.ExpireStalledAnswerGradingsUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AnswerGradingTimeoutSchedulerTest {
    @Test
    fun `watchdog is recorded as a managed batch job with expiration count`(): Unit = runBlocking {
        val executor = RecordingManagedJobs()
        val job = AnswerGradingWatchdogJob(
            object : ExpireStalledAnswerGradingsUseCase {
                override suspend fun expireStalled(now: Instant): Int = 3
            },
        )
        val scheduler = AnswerGradingTimeoutScheduler(executor, job)

        scheduler.expireStalled()

        assertThat(executor.jobName).isEqualTo("answer-grading-watchdog")
        assertThat(executor.triggerType).isEqualTo(JobTriggerType.SCHEDULED)
        assertThat(executor.summary).isEqualTo("expired=3")
    }

    private class RecordingManagedJobs : ManagedJobExecutionUseCase by unsupportedPort() {
        var jobName: String? = null
        var triggerType: JobTriggerType? = null
        var summary: String? = null

        override suspend fun execute(
            job: ManagedJob,
            triggerType: JobTriggerType,
            retryOfRunId: Long?,
            createdBy: String,
        ): ScheduledJobRun {
            jobName = job.name
            this.triggerType = triggerType
            summary = job.run()
            return ScheduledJobRun(
                id = 1,
                jobName = job.name,
                triggerType = triggerType,
                status = JobRunStatus.SUCCESS,
                startedAt = Instant.EPOCH,
                summary = summary,
            )
        }
    }

    private companion object {
        inline fun <reified T> unsupportedPort(): T =
            java.lang.reflect.Proxy.newProxyInstance(
                T::class.java.classLoader,
                arrayOf(T::class.java),
            ) { _, method, _ ->
                error("Unexpected ${T::class.simpleName} call: ${method.name}")
            } as T
    }
}
