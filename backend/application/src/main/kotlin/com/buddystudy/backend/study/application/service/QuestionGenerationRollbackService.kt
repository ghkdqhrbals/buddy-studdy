package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.study.application.model.ClaimedQuestionGenerationRollback
import com.buddystudy.backend.study.application.model.QuestionGenerationRollbackRequestedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.port.inbound.ProcessQuestionGenerationRollbackUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionGenerationRollbackWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class QuestionGenerationRollbackService(
    private val writer: QuestionGenerationRollbackWriteUseCase,
) : ProcessQuestionGenerationRollbackUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun process(event: QuestionGenerationRollbackRequestedEvent, streamKey: String) {
        val claimed = writer.claim(event, Instant.now(), streamKey) ?: return
        try {
            writer.complete(event, claimed.inbox, Instant.now())
            log.info(
                "question_generation_rollback_completed correlationId={} questionId={} failedStep={}",
                event.correlationId,
                event.questionId,
                event.failedStep,
            )
        } catch (error: Exception) {
            writer.retry(claimed.inbox, error.message ?: error.javaClass.simpleName, Instant.now())
            throw error
        }
    }
}

@Service
class QuestionGenerationRollbackWriteService(
    private val sagas: QuestionGenerationSagaPort,
    private val inbox: StreamInboxPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val questionCoverage: QuestionCoveragePort,
    private val memberships: QuestionMembershipPort,
) : QuestionGenerationRollbackWriteUseCase {
    @Transactional
    override suspend fun claim(
        event: QuestionGenerationRollbackRequestedEvent,
        now: Instant,
        streamKey: String,
    ): ClaimedQuestionGenerationRollback? {
        val inboxClaim = inbox.claim(
            eventId = event.eventId,
            consumerGroup = CONSUMER_GROUP,
            correlationId = event.correlationId,
            streamKey = streamKey,
            leaseDuration = LEASE_DURATION,
            now = now,
        ) ?: return null
        val saga = sagas.findByCorrelationId(event.correlationId)
        if (
            saga == null ||
            saga.status != QuestionGenerationStatus.FAILED ||
            saga.rollbackCompletedAt != null
        ) {
            check(inbox.markSucceeded(inboxClaim, now)) {
                "Failed to close an obsolete question rollback Inbox claim."
            }
            return null
        }
        return ClaimedQuestionGenerationRollback(saga, inboxClaim)
    }

    @Transactional
    override suspend fun complete(
        event: QuestionGenerationRollbackRequestedEvent,
        claim: StreamInboxClaim,
        now: Instant,
    ) {
        val saga = sagas.findByCorrelationId(event.correlationId)
            ?: error("Question generation Saga disappeared during rollback.")
        check(saga.status == QuestionGenerationStatus.FAILED) {
            "Only a failed question generation Saga can be rolled back."
        }
        if (saga.rollbackCompletedAt == null) {
            saga.questionId?.let { questionId ->
                questions.findQuestionById(questionId)
                    ?.let { question ->
                        check(question.userId == saga.userId) {
                            "Generated question owner does not match its Saga during rollback."
                        }
                        val conceptId = question.conceptId
                        val angleKey = question.angleKey
                        if (conceptId != null && !angleKey.isNullOrBlank()) {
                            questionCoverage.rollbackAsked(
                                conceptId = conceptId,
                                angleKey = angleKey,
                                now = now,
                            )
                        }
                        questionStats.deleteByQuestionId(questionId)
                        check(questions.deleteGeneratedForRollback(questionId, saga.userId) == 1) {
                            "Generated question was not deleted during rollback."
                        }
                    }
            }
            memberships.refundMonthlySystemQuestion(saga.userId, saga.quotaPeriodStartedAt, now)
            check(sagas.markRollbackCompleted(event.correlationId, now)) {
                "Question generation Saga rollback completion was not recorded."
            }
        }
        check(inbox.markSucceeded(claim, now)) {
            "Question generation rollback Inbox claim was lost before completion."
        }
    }

    @Transactional
    override suspend fun retry(claim: StreamInboxClaim, error: String, now: Instant) {
        check(inbox.releaseForRetry(claim, "QUESTION_GENERATION_ROLLBACK_FAILED", error, now)) {
            "Question generation rollback Inbox claim was lost before retry."
        }
    }

    companion object {
        const val CONSUMER_GROUP = "bs-backend-question-generation-rollback"
        const val RECOVERY_MIN_IDLE_TIME_MILLIS = 210_000L
        private val LEASE_DURATION: Duration = Duration.ofMinutes(3)
    }
}
