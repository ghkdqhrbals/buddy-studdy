package com.buddystudy.backend.study.adapter.inbound.scheduler

import com.buddystudy.backend.study.application.port.inbound.ExpireStalledAnswerGradingsUseCase
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AnswerGradingTimeoutScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val watchdogJob: AnswerGradingWatchdogJob,
) {
    @Scheduled(
        fixedDelayString = "\${buddystudy.openai.grading-watchdog-poll-ms:30000}",
        initialDelayString = "\${buddystudy.openai.grading-watchdog-initial-delay-ms:30000}",
    )
    suspend fun expireStalled() {
        jobs.execute(watchdogJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class AnswerGradingWatchdogJob(
    private val gradingTimeouts: ExpireStalledAnswerGradingsUseCase,
) : ManagedJob {
    override val name: String = "answer-grading-watchdog"
    override val displayName: String = "Answer grading timeout watchdog"
    override val description: String =
        "Marks answer grading requests as failed when they remain incomplete beyond the configured timeout."

    override suspend fun run(): String =
        "expired=${gradingTimeouts.expireStalled(Instant.now())}"
}
