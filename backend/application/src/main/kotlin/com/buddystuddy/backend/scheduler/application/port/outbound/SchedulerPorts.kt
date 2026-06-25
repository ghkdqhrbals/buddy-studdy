package com.buddystuddy.backend.scheduler.application.port.outbound

import com.buddystuddy.backend.scheduler.application.model.JobRunStatus
import com.buddystuddy.backend.scheduler.application.model.JobTriggerType
import com.buddystuddy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystuddy.backend.scheduler.application.model.ScheduledJobRunPageResponse

interface ScheduledJobRunPort {
    fun isEnabled(jobName: String): Boolean
    fun start(jobName: String, triggerType: JobTriggerType, retryOfRunId: Long?, createdBy: String): ScheduledJobRun
    fun finish(runId: Long, status: JobRunStatus, summary: String?, errorMessage: String?, durationMs: Long): ScheduledJobRun
    fun findRuns(jobName: String?, limit: Int, offset: Int): ScheduledJobRunPageResponse
}

interface JobLockPort {
    fun tryAcquire(jobName: String): Boolean
    fun release(jobName: String)
}
