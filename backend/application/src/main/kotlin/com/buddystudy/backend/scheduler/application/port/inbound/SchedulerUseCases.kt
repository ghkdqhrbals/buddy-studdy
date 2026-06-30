package com.buddystudy.backend.scheduler.application.port.inbound

import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse

interface ManagedJob {
    val name: String
    fun run(): String?
}

interface ManagedJobExecutionUseCase {
    fun execute(
        job: ManagedJob,
        triggerType: JobTriggerType,
        retryOfRunId: Long? = null,
        createdBy: String = "system",
    ): ScheduledJobRun

    fun findRuns(jobName: String? = null, limit: Int = 10, offset: Int = 0): ScheduledJobRunPageResponse
}
