package com.buddystudy.backend.voice.adapter.inbound.scheduler

import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRecordingRetentionUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class VoiceTutorRecordingRetentionScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val retentionJob: VoiceTutorRecordingRetentionJob,
) {
    @Scheduled(
        cron = "\${buddystudy.voice-tutor.recording-retention-cron:0 * * * * *}",
        zone = "\${buddystudy.voice-tutor.recording-retention-zone:UTC}",
    )
    suspend fun cleanup() {
        jobs.execute(retentionJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class VoiceTutorRecordingRetentionJob(
    private val retention: VoiceTutorRecordingRetentionUseCase,
) : ManagedJob {
    override val name: String = "voice-tutor-recording-retention"
    override val displayName: String = "Voice Tutor recording retention"
    override val description: String =
        "Deletes expired recordings and retries post-withdrawal owner-prefix tombstones in bounded batches."

    override suspend fun run(): String {
        val result = retention.cleanupExpired()
        return "deletedRecordings=${result.deletedRecordings},attemptedRecordings=${result.attemptedRecordings}," +
            "completedPrefixCleanups=${result.completedPrefixCleanups}," +
            "attemptedPrefixCleanups=${result.attemptedPrefixCleanups},capped=${result.capped}"
    }
}
