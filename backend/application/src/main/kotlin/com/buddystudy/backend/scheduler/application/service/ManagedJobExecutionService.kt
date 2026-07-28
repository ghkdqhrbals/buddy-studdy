package com.buddystudy.backend.scheduler.application.service

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystudy.backend.scheduler.application.model.ScheduledJobSnapshot
import com.buddystudy.backend.scheduler.application.model.ScheduledJobStatus
import com.buddystudy.backend.scheduler.application.model.ScheduledJobStatusResponse
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.scheduler.application.port.outbound.JobLockPort
import com.buddystudy.backend.scheduler.application.port.outbound.ScheduledJobRunPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class ManagedJobExecutionService(
    private val runs: ScheduledJobRunPort,
    private val locks: JobLockPort,
    private val properties: BuddyStudyProperties,
) : ManagedJobExecutionUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun execute(
        job: ManagedJob,
        triggerType: JobTriggerType,
        retryOfRunId: Long?,
        createdBy: String,
    ): ScheduledJobRun {
        if (!runs.isEnabled(job.name)) {
            val skipped = runs.start(job.name, triggerType, retryOfRunId, createdBy)
            return runs.finish(skipped.id, JobRunStatus.SKIPPED, null, "Job is disabled.", 0)
        }
        if (!locks.tryAcquire(job.name)) {
            val skipped = runs.start(job.name, triggerType, retryOfRunId, createdBy)
            return runs.finish(skipped.id, JobRunStatus.SKIPPED, null, "Job lock was not acquired.", 0)
        }

        val started = System.nanoTime()
        var run: ScheduledJobRun? = null
        return try {
            run = runs.start(job.name, triggerType, retryOfRunId, createdBy)
            val summary = job.run()
            runs.finish(run.id, JobRunStatus.SUCCESS, summary, null, elapsedMs(started))
        } catch (error: Exception) {
            val startedRun = run ?: throw error
            val failed = runs.finish(startedRun.id, JobRunStatus.FAILED, null, error.message ?: error.javaClass.simpleName, elapsedMs(started))
            logger.error(
                "scheduled_job_failed jobName={} runId={} retryOfRunId={} triggerType={} createdBy={} durationMs={} errorType={} error={}",
                failed.jobName,
                failed.id,
                failed.retryOfRunId,
                failed.triggerType,
                failed.createdBy,
                failed.durationMs,
                error.javaClass.name,
                error.message,
                error,
            )
            failed
        } finally {
            releaseLock(job.name)
        }
    }

    override suspend fun findRuns(jobName: String?, runId: Long?, limit: Int, offset: Int): ScheduledJobRunPageResponse =
        runs.findRuns(jobName, runId, limit.coerceIn(1, 200), offset.coerceAtLeast(0))

    override suspend fun findStatuses(): ScheduledJobStatusResponse {
        val monitoredJobs = properties.monitoring.schedulerMonitoredJobs
        val thresholdMinutes = properties.monitoring.schedulerStaleThresholdMinutes.coerceAtLeast(1)
        val threshold = Duration.ofMinutes(thresholdMinutes)
        val now = Instant.now()
        val snapshots = runs.findSnapshots(monitoredJobs).associateBy { it.jobName }
        val orderedSnapshots = if (monitoredJobs.isEmpty()) {
            snapshots.values.sortedBy { it.jobName }
        } else {
            monitoredJobs.map { jobName ->
                snapshots[jobName] ?: ScheduledJobSnapshot(
                    jobName = jobName,
                    enabled = true,
                    scheduleType = "MISSING",
                    scheduleValue = "not seeded",
                    timeoutSeconds = 300,
                    latestRun = null,
                    lastSuccessfulRun = null,
                )
            }
        }
        val jobs = orderedSnapshots.map { snapshot ->
            val latestRun = snapshot.latestRun
            val lastSuccessfulRun = snapshot.lastSuccessfulRun
            val stuck = snapshot.enabled &&
                latestRun?.status == JobRunStatus.RUNNING &&
                Duration.between(latestRun.startedAt, now).seconds > snapshot.timeoutSeconds.coerceAtLeast(1)
            val stale = snapshot.enabled &&
                (
                    lastSuccessfulRun == null ||
                        latestRun?.status == JobRunStatus.FAILED ||
                        stuck ||
                        Duration.between(lastSuccessfulRun.startedAt, now) > threshold
                    )
            ScheduledJobStatus(
                jobName = snapshot.jobName,
                enabled = snapshot.enabled,
                scheduleType = snapshot.scheduleType,
                scheduleValue = snapshot.scheduleValue,
                latestRun = latestRun,
                lastSuccessfulRun = lastSuccessfulRun,
                stale = stale,
                staleThresholdMinutes = thresholdMinutes,
                timeoutSeconds = snapshot.timeoutSeconds.coerceAtLeast(1),
                stuck = stuck,
            )
        }
        return ScheduledJobStatusResponse(jobs)
    }

    private suspend fun elapsedMs(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)

    private suspend fun releaseLock(jobName: String) {
        runCatching { locks.release(jobName) }
            .onFailure { error ->
                logger.error(
                    "scheduled_job_lock_release_failed jobName={} errorType={} error={}",
                    jobName,
                    error.javaClass.name,
                    error.message,
                    error,
                )
            }
    }
}
