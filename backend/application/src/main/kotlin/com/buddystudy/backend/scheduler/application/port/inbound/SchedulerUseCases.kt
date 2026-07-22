package com.buddystudy.backend.scheduler.application.port.inbound

import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystudy.backend.scheduler.application.model.ScheduledJobStatusResponse

interface ManagedJob {
    val name: String
    suspend fun run(): String?
}

interface ManagedJobExecutionUseCase {
    suspend fun execute(
        job: ManagedJob,
        triggerType: JobTriggerType,
        retryOfRunId: Long? = null,
        createdBy: String = "system",
    ): ScheduledJobRun

    suspend fun findRuns(jobName: String? = null, runId: Long? = null, limit: Int = 10, offset: Int = 0): ScheduledJobRunPageResponse

    suspend fun findStatuses(): ScheduledJobStatusResponse
}
