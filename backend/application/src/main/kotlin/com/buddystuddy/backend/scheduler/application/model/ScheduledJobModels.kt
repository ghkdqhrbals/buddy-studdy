package com.buddystuddy.backend.scheduler.application.model

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
)
