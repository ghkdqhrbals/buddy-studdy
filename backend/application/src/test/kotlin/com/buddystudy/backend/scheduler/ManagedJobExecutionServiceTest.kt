package com.buddystudy.backend.scheduler

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystudy.backend.scheduler.application.model.ScheduledJobSnapshot
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.outbound.JobLockPort
import com.buddystudy.backend.scheduler.application.port.outbound.ScheduledJobAlertPort
import com.buddystudy.backend.scheduler.application.port.outbound.ScheduledJobRunPort
import com.buddystudy.backend.scheduler.application.service.ManagedJobExecutionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ManagedJobExecutionServiceTest {
    private val runs = FakeScheduledJobRunPort()
    private val locks = FakeJobLockPort()
    private val alerts = FakeScheduledJobAlertPort()
    private val properties = BuddyStudyProperties(
        monitoring = BuddyStudyProperties.Monitoring(
            schedulerStaleThresholdMinutes = 15,
            schedulerMonitoredJobs = listOf("question-schedule", "user-stats-refresh"),
        ),
    )
    private val service = ManagedJobExecutionService(runs, locks, alerts, properties)

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
        assertThat(alerts.failedRuns).containsExactly(result)
    }

    @Test
    fun `execute records failed job run even when alert delivery fails`() {
        alerts.error = IllegalStateException("slack unavailable")

        val result = service.execute(FakeJob("user-stats-refresh") { error("boom") }, JobTriggerType.SCHEDULED)

        assertThat(result.status).isEqualTo(JobRunStatus.FAILED)
        assertThat(result.errorMessage).contains("boom")
        assertThat(runs.rows.single().status).isEqualTo(JobRunStatus.FAILED)
        assertThat(locks.released).containsExactly("user-stats-refresh")
    }

    @Test
    fun `execute releases lock when run start fails`() {
        runs.startError = IllegalStateException("database unavailable")

        val error = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            service.execute(FakeJob("user-stats-refresh") { "done" }, JobTriggerType.SCHEDULED)
        }

        assertThat(error.message).contains("database unavailable")
        assertThat(locks.released).containsExactly("user-stats-refresh")
    }

    @Test
    fun `execute keeps successful job result when lock release fails`() {
        locks.releaseError = IllegalStateException("unlock failed")

        val result = service.execute(FakeJob("user-stats-refresh") { "done" }, JobTriggerType.SCHEDULED)

        assertThat(result.status).isEqualTo(JobRunStatus.SUCCESS)
        assertThat(result.summary).isEqualTo("done")
        assertThat(locks.released).containsExactly("user-stats-refresh")
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
    fun `execute skips locked job without running work or sending alerts`() {
        locks.acquired = false
        var executed = false

        val result = service.execute(FakeJob("question-schedule") { executed = true; "done" }, JobTriggerType.SCHEDULED)

        assertThat(executed).isFalse()
        assertThat(result.status).isEqualTo(JobRunStatus.SKIPPED)
        assertThat(result.errorMessage).isEqualTo("Job lock was not acquired.")
        assertThat(alerts.failedRuns).isEmpty()
        assertThat(locks.released).isEmpty()
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

    @Test
    fun `find statuses marks missing and failed enabled jobs as stale`() {
        runs.snapshots += ScheduledJobSnapshot(
            jobName = "question-schedule",
            enabled = true,
            scheduleType = "FIXED_DELAY",
            scheduleValue = "30s",
            latestRun = null,
        )
        runs.snapshots += ScheduledJobSnapshot(
            jobName = "user-stats-refresh",
            enabled = true,
            scheduleType = "CRON",
            scheduleValue = "0 */5 * * * *",
            latestRun = ScheduledJobRun(
                id = 9,
                jobName = "user-stats-refresh",
                triggerType = JobTriggerType.SCHEDULED,
                status = JobRunStatus.FAILED,
                startedAt = Instant.now(),
                errorMessage = "boom",
            ),
        )

        val response = service.findStatuses()

        assertThat(response.jobs).hasSize(2)
        assertThat(response.jobs.map { it.jobName }).containsExactly("question-schedule", "user-stats-refresh")
        assertThat(response.jobs).allMatch { it.stale }
        assertThat(response.jobs).allMatch { it.staleThresholdMinutes == 15L }
    }

    @Test
    fun `find statuses includes monitored jobs that are missing from scheduler seed table`() {
        runs.snapshots += ScheduledJobSnapshot(
            jobName = "question-schedule",
            enabled = true,
            scheduleType = "FIXED_DELAY",
            scheduleValue = "30s",
            latestRun = ScheduledJobRun(
                id = 7,
                jobName = "question-schedule",
                triggerType = JobTriggerType.SCHEDULED,
                status = JobRunStatus.SUCCESS,
                startedAt = Instant.now(),
            ),
        )

        val response = service.findStatuses()

        assertThat(response.jobs.map { it.jobName }).containsExactly("question-schedule", "user-stats-refresh")
        val missing = response.jobs.single { it.jobName == "user-stats-refresh" }
        assertThat(missing.enabled).isTrue()
        assertThat(missing.scheduleType).isEqualTo("MISSING")
        assertThat(missing.scheduleValue).isEqualTo("not seeded")
        assertThat(missing.latestRun).isNull()
        assertThat(missing.stale).isTrue()
    }

    @Test
    fun `find statuses ignores disabled stale jobs`() {
        runs.snapshots += ScheduledJobSnapshot(
            jobName = "question-schedule",
            enabled = false,
            scheduleType = "FIXED_DELAY",
            scheduleValue = "30s",
            latestRun = null,
        )

        val response = service.findStatuses()

        assertThat(response.jobs.single { it.jobName == "question-schedule" }.stale).isFalse()
    }

    @Test
    fun `find statuses marks running job past timeout as stuck and stale`() {
        runs.snapshots += ScheduledJobSnapshot(
            jobName = "question-schedule",
            enabled = true,
            scheduleType = "FIXED_DELAY",
            scheduleValue = "30s",
            timeoutSeconds = 60,
            latestRun = ScheduledJobRun(
                id = 12,
                jobName = "question-schedule",
                triggerType = JobTriggerType.SCHEDULED,
                status = JobRunStatus.RUNNING,
                startedAt = Instant.now().minusSeconds(120),
            ),
        )

        val response = service.findStatuses()

        val status = response.jobs.single { it.jobName == "question-schedule" }
        assertThat(status.stuck).isTrue()
        assertThat(status.stale).isTrue()
        assertThat(status.timeoutSeconds).isEqualTo(60)
    }

    private class FakeJob(
        override val name: String,
        private val block: () -> String,
    ) : ManagedJob {
        override fun run(): String = block()
    }

    private class FakeJobLockPort : JobLockPort {
        val released = mutableListOf<String>()
        var acquired = true
        var releaseError: RuntimeException? = null
        override fun tryAcquire(jobName: String): Boolean = acquired
        override fun release(jobName: String) {
            released += jobName
            releaseError?.let { throw it }
        }
    }

    private class FakeScheduledJobAlertPort : ScheduledJobAlertPort {
        val failedRuns = mutableListOf<ScheduledJobRun>()
        var error: RuntimeException? = null

        override fun notifyFailed(run: ScheduledJobRun) {
            error?.let { throw it }
            failedRuns += run
        }
    }

    private class FakeScheduledJobRunPort : ScheduledJobRunPort {
        val enabled = mutableMapOf<String, Boolean>()
        val rows = mutableListOf<ScheduledJobRun>()
        val snapshots = mutableListOf<ScheduledJobSnapshot>()
        var startError: RuntimeException? = null
        private var nextId = 1L

        override fun isEnabled(jobName: String): Boolean = enabled[jobName] ?: true

        override fun start(jobName: String, triggerType: JobTriggerType, retryOfRunId: Long?, createdBy: String): ScheduledJobRun {
            startError?.let { throw it }
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

        override fun findRuns(jobName: String?, limit: Int, offset: Int): ScheduledJobRunPageResponse {
            val filtered = rows.filter { jobName == null || it.jobName == jobName }
            return ScheduledJobRunPageResponse(filtered.drop(offset).take(limit), filtered.size.toLong(), limit, offset)
        }

        override fun findSnapshots(jobNames: List<String>): List<ScheduledJobSnapshot> =
            snapshots.filter { jobNames.isEmpty() || it.jobName in jobNames }
    }
}
