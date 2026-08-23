package com.buddystudy.backend.billing.adapter.inbound.scheduler

import com.buddystudy.backend.billing.application.model.BillingRecoveryResult
import com.buddystudy.backend.billing.application.port.inbound.BillingRecoveryUseCase
import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class BillingRecoverySchedulerTest {
    @Test
    fun `billing recovery is visible as a managed job and reports outcomes`(): Unit = runBlocking {
        val executor = RecordingManagedJobs()
        val job = BillingRecoveryJob(
            object : BillingRecoveryUseCase {
                override suspend fun recoverDueFulfillments() = BillingRecoveryResult(3, 4, 2, 1, 1)
            },
        )
        val scheduler = BillingRecoveryScheduler(executor, job)

        scheduler.recover()

        assertThat(executor.jobName).isEqualTo("billing-fulfillment-recovery")
        assertThat(executor.triggerType).isEqualTo(JobTriggerType.SCHEDULED)
        assertThat(executor.summary).isEqualTo(
            "expiredCheckouts=3, claimed=4, completed=2, retried=1, compensationRequired=1",
        )
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
