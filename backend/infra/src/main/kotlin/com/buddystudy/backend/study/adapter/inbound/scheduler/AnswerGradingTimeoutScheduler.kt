package com.buddystudy.backend.study.adapter.inbound.scheduler

import com.buddystudy.backend.study.application.port.inbound.ExpireStalledAnswerGradingsUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AnswerGradingTimeoutScheduler(
    private val gradingTimeouts: ExpireStalledAnswerGradingsUseCase,
) {
    @Scheduled(
        fixedDelayString = "\${buddystudy.openai.grading-watchdog-poll-ms:30000}",
        initialDelayString = "\${buddystudy.openai.grading-watchdog-initial-delay-ms:30000}",
    )
    suspend fun expireStalled() {
        gradingTimeouts.expireStalled(Instant.now())
    }
}
