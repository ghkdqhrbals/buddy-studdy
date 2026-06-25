package com.buddystuddy.backend.scheduler.application.port.inbound

import com.buddystuddy.backend.scheduler.application.model.JobTriggerType
import com.buddystuddy.backend.scheduler.application.model.ScheduledJobRun

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

    fun findRuns(jobName: String? = null, limit: Int = 50): List<ScheduledJobRun>
}
