package com.buddystudy.backend.scheduler.application.model

import com.buddystudy.backend.common.application.model.PageResponse
import java.time.Instant

enum class JobTriggerType {
    SCHEDULED,
    MANUAL,
    RETRY,
}

enum class JobRunStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
}

data class ScheduledJobRun(
    val id: Long,
    val jobName: String,
    val triggerType: JobTriggerType,
    val status: JobRunStatus,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val durationMs: Long? = null,
    val summary: String? = null,
    val errorMessage: String? = null,
    val retryOfRunId: Long? = null,
    val createdBy: String = "system",
    val displayName: String? = null,
)

data class ScheduledJobRunPageResponse(
    val runs: List<ScheduledJobRun>,
    val limit: Int,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

data class ScheduledJobHistoryCleanupResult(
    val deletedRuns: Int,
    val batches: Int,
    val capped: Boolean,
    val cutoff: Instant,
)

data class ScheduledJobSnapshot(
    val jobName: String,
    val enabled: Boolean,
    val scheduleType: String,
    val scheduleValue: String,
    val timeoutSeconds: Int = 300,
    val latestRun: ScheduledJobRun?,
    val lastSuccessfulRun: ScheduledJobRun? = null,
)

data class ScheduledJobSnapshotPage(
    val snapshots: List<ScheduledJobSnapshot>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
)

data class ScheduledJobStatus(
    val jobName: String,
    val displayName: String = jobName,
    val description: String = "",
    val enabled: Boolean,
    val monitored: Boolean = false,
    val scheduleType: String,
    val scheduleValue: String,
    val latestRun: ScheduledJobRun?,
    val lastSuccessfulRun: ScheduledJobRun?,
    val stale: Boolean,
    val staleThresholdMinutes: Long,
    val timeoutSeconds: Int,
    val stuck: Boolean,
)

data class ScheduledJobStatusResponse(
    val jobs: List<ScheduledJobStatus>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
) : PageResponse
