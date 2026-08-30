package com.buddystudy.backend.voice.adapter.inbound.scheduler

import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingRetentionResult
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRecordingRetentionUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceTutorRecordingRetentionSchedulerTest {
    @Test
    fun `retention cleanup is executed as the managed recording job`() = runBlocking<Unit> {
        val executor = RecordingManagedJobs()
        val job = VoiceTutorRecordingRetentionJob(
            object : VoiceTutorRecordingRetentionUseCase {
                override suspend fun cleanupExpired() = VoiceTutorRecordingRetentionResult(
                    deletedRecordings = 3,
                    attemptedRecordings = 4,
                    capped = true,
                    completedPrefixCleanups = 1,
                    attemptedPrefixCleanups = 2,
                )
            },
        )

        VoiceTutorRecordingRetentionScheduler(executor, job).cleanup()

        assertThat(executor.jobName).isEqualTo("voice-tutor-recording-retention")
        assertThat(executor.triggerType).isEqualTo(JobTriggerType.SCHEDULED)
        assertThat(executor.summary).isEqualTo(
            "deletedRecordings=3,attemptedRecordings=4," +
                "completedPrefixCleanups=1,attemptedPrefixCleanups=2,capped=true",
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
            ) { _, method, _ -> error("Unexpected ${T::class.simpleName} call: ${method.name}") } as T
    }
}
