package com.buddystudy.backend.scheduler.application.port.inbound

import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobHistoryCleanupResult
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystudy.backend.scheduler.application.model.ScheduledJobStatusResponse
import java.time.Instant

interface ManagedJob {
    val name: String
    val displayName: String
        get() = name
    val description: String
        get() = ""
    suspend fun run(): String?
}

interface ManagedJobExecutionUseCase {
    suspend fun execute(
        job: ManagedJob,
        triggerType: JobTriggerType,
        retryOfRunId: Long? = null,
        createdBy: String = "system",
    ): ScheduledJobRun

    suspend fun findRuns(jobName: String? = null, runId: Long? = null, limit: Int = 10, cursor: Long? = null): ScheduledJobRunPageResponse

    suspend fun findStatuses(limit: Int? = null, offset: Int = 0): ScheduledJobStatusResponse
}

interface ScheduledJobHistoryCleanupUseCase {
    suspend fun cleanup(now: Instant = Instant.now()): ScheduledJobHistoryCleanupResult
}
