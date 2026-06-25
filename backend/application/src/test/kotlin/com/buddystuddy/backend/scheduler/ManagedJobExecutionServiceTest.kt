package com.buddystuddy.backend.scheduler

import com.buddystuddy.backend.scheduler.application.model.JobRunStatus
import com.buddystuddy.backend.scheduler.application.model.JobTriggerType
import com.buddystuddy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystuddy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystuddy.backend.scheduler.application.port.outbound.JobLockPort
import com.buddystuddy.backend.scheduler.application.port.outbound.ScheduledJobRunPort
import com.buddystuddy.backend.scheduler.application.service.ManagedJobExecutionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ManagedJobExecutionServiceTest {
    private val runs = FakeScheduledJobRunPort()
    private val locks = FakeJobLockPort()
    private val service = ManagedJobExecutionService(runs, locks)

    @Test
    fun `execute records successful job run`() {
        val result = service.execute(FakeJob("admin-analytics-recent") { "rows=18" }, JobTriggerType.SCHEDULED)

        assertThat(result.status).isEqualTo(JobRunStatus.SUCCESS)
        assertThat(result.summary).isEqualTo("rows=18")
        assertThat(runs.rows.single().status).isEqualTo(JobRunStatus.SUCCESS)
        assertThat(runs.rows.single().durationMs).isNotNull()
    }

    @Test
    fun `execute records failed job run without throwing`() {
        val result = service.execute(FakeJob("user-stats-refresh") { error("boom") }, JobTriggerType.SCHEDULED)

        assertThat(result.status).isEqualTo(JobRunStatus.FAILED)
        assertThat(result.errorMessage).contains("boom")
        assertThat(runs.rows.single().status).isEqualTo(JobRunStatus.FAILED)
    }

    @Test
    fun `execute skips disabled job without running work`() {
        runs.enabled["user-stats-refresh"] = false
        var executed = false

        val result = service.execute(FakeJob("user-stats-refresh") { executed = true; "done" }, JobTriggerType.SCHEDULED)

        assertThat(executed).isFalse()
        assertThat(result.status).isEqualTo(JobRunStatus.SKIPPED)
        assertThat(result.errorMessage).isEqualTo("Job is disabled.")
    }

    @Test
    fun `retry keeps retry source run id`() {
        val result = service.execute(
            FakeJob("admin-analytics-correction") { "corrected" },
            JobTriggerType.RETRY,
            retryOfRunId = 42,
            createdBy = "admin",
        )

        assertThat(result.retryOfRunId).isEqualTo(42)
        assertThat(result.triggerType).isEqualTo(JobTriggerType.RETRY)
        assertThat(result.createdBy).isEqualTo("admin")
    }

    private class FakeJob(
        override val name: String,
        private val block: () -> String,
    ) : ManagedJob {
        override fun run(): String = block()
    }

    private class FakeJobLockPort : JobLockPort {
        override fun tryAcquire(jobName: String): Boolean = true
        override fun release(jobName: String) = Unit
    }

    private class FakeScheduledJobRunPort : ScheduledJobRunPort {
        val enabled = mutableMapOf<String, Boolean>()
        val rows = mutableListOf<ScheduledJobRun>()
        private var nextId = 1L

        override fun isEnabled(jobName: String): Boolean = enabled[jobName] ?: true

        override fun start(jobName: String, triggerType: JobTriggerType, retryOfRunId: Long?, createdBy: String): ScheduledJobRun {
            val row = ScheduledJobRun(
                id = nextId++,
                jobName = jobName,
                triggerType = triggerType,
                status = JobRunStatus.RUNNING,
                startedAt = Instant.now(),
                retryOfRunId = retryOfRunId,
                createdBy = createdBy,
            )
            rows += row
            return row
        }

        override fun finish(runId: Long, status: JobRunStatus, summary: String?, errorMessage: String?, durationMs: Long): ScheduledJobRun {
            val index = rows.indexOfFirst { it.id == runId }
            val updated = rows[index].copy(
                status = status,
                summary = summary,
                errorMessage = errorMessage,
                durationMs = durationMs,
                finishedAt = Instant.now(),
            )
            rows[index] = updated
            return updated
        }

        override fun findRuns(jobName: String?, limit: Int): List<ScheduledJobRun> =
            rows.filter { jobName == null || it.jobName == jobName }.take(limit)
    }
}
