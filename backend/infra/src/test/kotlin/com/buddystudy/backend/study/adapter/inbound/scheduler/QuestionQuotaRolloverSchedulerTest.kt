package com.buddystudy.backend.study.adapter.inbound.scheduler

import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionQuotaRolloverResult
import com.buddystudy.backend.study.application.port.inbound.QuestionQuotaRolloverUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class QuestionQuotaRolloverSchedulerTest {
    @Test
    fun `quota rollover is executed as a managed job and reports the reset count`(): Unit = runBlocking {
        val executor = RecordingManagedJobs()
        val job = QuestionQuotaRolloverJob(
            object : QuestionQuotaRolloverUseCase {
                override suspend fun rolloverDue(at: Instant) = QuestionQuotaRolloverResult(7)
                override suspend fun rolloverUserIfDue(userId: Long, at: Instant): Boolean = false
            },
        )
        val scheduler = QuestionQuotaRolloverScheduler(executor, job)

        scheduler.rollover()

        assertThat(executor.jobName).isEqualTo("user-quota-rollover")
        assertThat(executor.triggerType).isEqualTo(JobTriggerType.SCHEDULED)
        assertThat(executor.summary).isEqualTo("rolledOver=7")
        assertThat(job.displayName).isEqualTo("Monthly question quota rollover")
        assertThat(job.description).contains("append-only quota history")
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
