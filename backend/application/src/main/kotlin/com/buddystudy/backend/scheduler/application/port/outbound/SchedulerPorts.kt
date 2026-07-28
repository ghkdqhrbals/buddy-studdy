package com.buddystudy.backend.scheduler.application.port.outbound

import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystudy.backend.scheduler.application.model.ScheduledJobSnapshot

interface ScheduledJobRunPort {
    suspend fun isEnabled(jobName: String): Boolean
    suspend fun start(jobName: String, triggerType: JobTriggerType, retryOfRunId: Long?, createdBy: String): ScheduledJobRun
    suspend fun finish(runId: Long, status: JobRunStatus, summary: String?, errorMessage: String?, durationMs: Long): ScheduledJobRun
    suspend fun findRuns(jobName: String?, runId: Long?, limit: Int, offset: Int): ScheduledJobRunPageResponse
    suspend fun findSnapshots(jobNames: List<String>): List<ScheduledJobSnapshot>
}

interface JobLockPort {
    suspend fun tryAcquire(jobName: String): Boolean
    suspend fun release(jobName: String)
}
