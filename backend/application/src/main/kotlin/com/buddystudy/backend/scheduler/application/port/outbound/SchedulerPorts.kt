package com.buddystudy.backend.scheduler.application.port.outbound

import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystudy.backend.scheduler.application.model.ScheduledJobSnapshot

interface ScheduledJobRunPort {
    fun isEnabled(jobName: String): Boolean
    fun start(jobName: String, triggerType: JobTriggerType, retryOfRunId: Long?, createdBy: String): ScheduledJobRun
    fun finish(runId: Long, status: JobRunStatus, summary: String?, errorMessage: String?, durationMs: Long): ScheduledJobRun
    fun findRuns(jobName: String?, limit: Int, offset: Int): ScheduledJobRunPageResponse
    fun findSnapshots(jobNames: List<String>): List<ScheduledJobSnapshot>
}

interface ScheduledJobAlertPort {
    fun notifyFailed(run: ScheduledJobRun)
}

interface JobLockPort {
    fun tryAcquire(jobName: String): Boolean
    fun release(jobName: String)
}
