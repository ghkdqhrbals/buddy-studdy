package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.backend.study.application.port.inbound.ExpireStalledAnswerGradingsUseCase
import com.buddystudy.backend.study.application.port.outbound.AnswerGradingProgressPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AnswerGradingTimeoutService(
    private val properties: BuddyStudyProperties,
    private val questions: QuestionPort,
    private val gradingProgress: AnswerGradingProgressPort,
) : ExpireStalledAnswerGradingsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override suspend fun expireStalled(now: Instant): Int {
        val timeoutSeconds = properties.openai.gradingTimeoutSeconds.coerceIn(30, MAX_TIMEOUT_SECONDS)
        val cutoff = now.minusSeconds(timeoutSeconds)
        var expired = 0
        questions.findStalledGradings(cutoff, BATCH_SIZE).forEach { question ->
            val userId = question.userId ?: return@forEach
            val requestId = question.gradingRequestId ?: return@forEach
            if (!questions.failStalledGrading(question.id, requestId, cutoff, TIMEOUT_MESSAGE, now)) {
                return@forEach
            }
            val progress = gradingProgress.append(
                recordId = question.id,
                userId = userId,
                requestId = requestId,
                status = AnswerGradingStatus.FAILED,
                questionStatus = QuestionStatus.FAILED,
                errorMessage = TIMEOUT_MESSAGE,
                occurredAt = now,
            )
            check(questions.updateGradingLastEventId(question.id, requestId, progress.id)) {
                "Failed to update grading event cursor for question ${question.id}."
            }
            expired += 1
            log.warn(
                "answer_grading_watchdog_expired recordId={} requestId={} requestedAt={} timeoutSeconds={}",
                question.id,
                requestId,
                question.gradingRequestedAt,
                timeoutSeconds,
            )
        }
        return expired
    }

    private companion object {
        const val MAX_TIMEOUT_SECONDS = 270L
        const val BATCH_SIZE = 100
        const val TIMEOUT_MESSAGE = "채점 시간이 초과되었습니다. 다시 시도해 주세요."
    }
}
