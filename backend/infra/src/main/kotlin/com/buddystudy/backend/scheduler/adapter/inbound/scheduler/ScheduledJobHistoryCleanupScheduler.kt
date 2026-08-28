package com.buddystudy.backend.scheduler.adapter.inbound.scheduler

import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.scheduler.application.port.inbound.ScheduledJobHistoryCleanupUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ScheduledJobHistoryCleanupScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val cleanupJob: ScheduledJobHistoryCleanupJob,
) {
    @Scheduled(
        cron = "\${buddystudy.scheduler.history-cleanup-cron:0 40 3 * * *}",
        zone = "\${buddystudy.scheduler.history-cleanup-zone:UTC}",
    )
    suspend fun cleanup() {
        jobs.execute(cleanupJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class ScheduledJobHistoryCleanupJob(
    private val cleanup: ScheduledJobHistoryCleanupUseCase,
) : ManagedJob {
    override val name: String = "scheduled-job-history-cleanup"
    override val displayName: String = "Scheduled job history cleanup"
    override val description: String =
        "Deletes expired scheduler run history in bounded batches while preserving active and referenced retry runs."

    override suspend fun run(): String {
        val result = cleanup.cleanup()
        return "deletedRuns=${result.deletedRuns},batches=${result.batches},capped=${result.capped}"
    }
}
