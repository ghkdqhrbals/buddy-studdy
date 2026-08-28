package com.buddystudy.backend.scheduler.application.service

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.ScheduledJobHistoryCleanupResult
import com.buddystudy.backend.scheduler.application.port.inbound.ScheduledJobHistoryCleanupUseCase
import com.buddystudy.backend.scheduler.application.port.outbound.ScheduledJobHistoryRetentionPort
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class ScheduledJobHistoryCleanupService(
    private val history: ScheduledJobHistoryRetentionPort,
    private val properties: BuddyStudyProperties,
) : ScheduledJobHistoryCleanupUseCase {
    override suspend fun cleanup(now: Instant): ScheduledJobHistoryCleanupResult {
        val scheduler = properties.scheduler
        val successRetentionDays = scheduler.historySuccessRetentionDays.coerceAtLeast(1)
        val failureRetentionDays = scheduler.historyFailureRetentionDays.coerceAtLeast(successRetentionDays)
        val batchSize = scheduler.historyCleanupBatchSize.coerceIn(1, MAX_BATCH_SIZE)
        val maxRows = scheduler.historyCleanupMaxRowsPerRun.coerceAtLeast(1)
        val successCutoff = now.minus(Duration.ofDays(successRetentionDays))
        val failureCutoff = now.minus(Duration.ofDays(failureRetentionDays))

        var deletedRuns = 0
        var batches = 0
        while (deletedRuns < maxRows) {
            val limit = minOf(batchSize, maxRows - deletedRuns)
            val deleted = history.deleteExpiredTerminalRuns(successCutoff, failureCutoff, limit)
                .coerceIn(0, limit)
            if (deleted == 0) break
            deletedRuns += deleted
            batches += 1
        }

        return ScheduledJobHistoryCleanupResult(
            deletedRuns = deletedRuns,
            batches = batches,
            capped = deletedRuns >= maxRows,
            successCutoff = successCutoff,
            failureCutoff = failureCutoff,
        )
    }

    private companion object {
        const val MAX_BATCH_SIZE = 50_000
    }
}
