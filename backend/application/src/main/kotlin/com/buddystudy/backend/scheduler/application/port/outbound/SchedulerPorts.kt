package com.buddystudy.backend.scheduler.application.port.outbound

import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystudy.backend.scheduler.application.model.ScheduledJobSnapshotPage
import java.time.Instant

interface ScheduledJobRunPort {
    suspend fun isEnabled(jobName: String): Boolean
    suspend fun start(jobName: String, triggerType: JobTriggerType, retryOfRunId: Long?, createdBy: String): ScheduledJobRun
    suspend fun finish(runId: Long, status: JobRunStatus, summary: String?, errorMessage: String?, durationMs: Long): ScheduledJobRun
    suspend fun findRuns(jobName: String?, runId: Long?, limit: Int, cursor: Long?): ScheduledJobRunPageResponse
    suspend fun findSnapshotPage(limit: Int, offset: Int): ScheduledJobSnapshotPage
    suspend fun findExistingJobNames(jobNames: List<String>): Set<String>
}

interface ScheduledJobHistoryRetentionPort {
    suspend fun deleteExpiredTerminalRuns(
        cutoff: Instant,
        limit: Int,
    ): Int
}

interface JobLockPort {
    suspend fun tryAcquire(jobName: String): Boolean
    suspend fun release(jobName: String)
}
