package com.buddystudy.backend.scheduler.application.service

import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.scheduler.application.port.outbound.JobLockPort
import com.buddystudy.backend.scheduler.application.port.outbound.ScheduledJobRunPort
import org.springframework.stereotype.Service

@Service
class ManagedJobExecutionService(
    private val runs: ScheduledJobRunPort,
    private val locks: JobLockPort,
) : ManagedJobExecutionUseCase {
    override fun execute(
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
        val run = runs.start(job.name, triggerType, retryOfRunId, createdBy)
        return try {
            val summary = job.run()
            runs.finish(run.id, JobRunStatus.SUCCESS, summary, null, elapsedMs(started))
        } catch (error: Exception) {
            runs.finish(run.id, JobRunStatus.FAILED, null, error.message ?: error.javaClass.simpleName, elapsedMs(started))
        } finally {
            locks.release(job.name)
        }
    }

    override fun findRuns(jobName: String?, limit: Int, offset: Int): ScheduledJobRunPageResponse =
        runs.findRuns(jobName, limit.coerceIn(1, 200), offset.coerceAtLeast(0))

    private fun elapsedMs(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
}
