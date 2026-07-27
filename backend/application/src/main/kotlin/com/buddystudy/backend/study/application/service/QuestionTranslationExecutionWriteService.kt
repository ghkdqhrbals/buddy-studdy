package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.study.application.model.ClaimedQuestionTranslation
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.port.inbound.QuestionTranslationExecutionWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushOutboxAppendPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.StudyEntity
import com.buddystudy.study.domain.localizedFor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class QuestionTranslationExecutionWriteService(
    private val sagas: QuestionGenerationSagaPort,
    private val inbox: StreamInboxPort,
    private val questions: QuestionPort,
    private val notificationOutbox: RedisEventOutboxAppendPort,
    private val pushOutbox: QuestionPushOutboxAppendPort,
    private val questionKeys: OpenAIQuestionKeyProvider,
) : QuestionTranslationExecutionWriteUseCase {
    @Transactional
    override suspend fun claim(event: QuestionGeneratedEvent, now: Instant): ClaimedQuestionTranslation? {
        val inboxClaim = inbox.claim(
            eventId = event.eventId,
            consumerGroup = CONSUMER_GROUP,
            correlationId = event.correlationId,
            leaseDuration = LEASE_DURATION,
            now = now,
        ) ?: return null
        val saga = sagas.findByCorrelationId(event.correlationId)
        if (
            saga == null ||
            saga.status != QuestionGenerationStatus.TRANSLATING ||
            saga.questionId != event.questionId
        ) {
            check(inbox.markSucceeded(inboxClaim, now)) {
                "Failed to close a terminal translation Inbox claim."
            }
            return null
        }
        return ClaimedQuestionTranslation(saga, inboxClaim)
    }

    @Transactional
    override suspend fun complete(
        event: QuestionGeneratedEvent,
        claim: StreamInboxClaim,
        translation: TranslatedQuestionContent?,
        rootStudy: StudyEntity,
        appLanguage: String,
        now: Instant,
    ): QuestionWriteResult {
        if (translation != null) {
            check(
                questions.saveEnglishTranslation(
                    questionId = event.questionId,
                    question = translation.question,
                    hint = translation.hint,
                    now = now,
                ),
            ) { "Question disappeared while saving its translation." }
        }
        val question = checkNotNull(questions.findQuestionById(event.questionId)) {
            "Question disappeared before delivery."
        }.localizedFor(QuestionLanguage.normalize(appLanguage))
        val notificationId = notificationOutbox.appendNotification(
            question.toQuestionNotification(rootStudy, appLanguage),
            now,
        )
        val pushId = pushOutbox.enqueue(
            question.toQuestionPushRequest(rootStudy, appLanguage),
            now,
        )
        check(sagas.markCompleted(event.correlationId, now)) {
            "Question generation Saga did not enter COMPLETED."
        }
        check(inbox.markSucceeded(claim, now)) {
            "Question translation Inbox claim was lost before completion."
        }
        return QuestionWriteResult(
            question = question,
            outboxes = listOf(
                OutboxReference(OutboxType.DOMAIN_EVENT, notificationId),
                OutboxReference(OutboxType.QUESTION_PUSH, pushId),
            ),
        )
    }

    @Transactional
    override suspend fun retry(claim: StreamInboxClaim, error: String, now: Instant) {
        check(inbox.releaseForRetry(claim, error, now)) {
            "Question translation Inbox claim was lost before retry."
        }
    }

    @Transactional
    override suspend fun fail(
        event: QuestionGeneratedEvent,
        claim: StreamInboxClaim,
        errorMessage: String,
        now: Instant,
    ) {
        val saga = sagas.findByCorrelationId(event.correlationId)
        if (saga != null && saga.status !in setOf(QuestionGenerationStatus.COMPLETED, QuestionGenerationStatus.FAILED)) {
            questions.markEnglishTranslationFailed(event.questionId, errorMessage, now)
            questions.softDelete(event.questionId, saga.userId, now)
            check(
                sagas.markFailed(
                    correlationId = saga.correlationId,
                    failedStep = QuestionGenerationStep.TRANSLATING,
                    errorCode = "QUESTION_TRANSLATION_FAILED",
                    errorMessage = "질문 번역을 완료하지 못했습니다.",
                    refundedAt = now,
                    now = now,
                ),
            ) { "Question generation Saga did not enter FAILED during translation." }
            if (saga.quotaRefundedAt == null) {
                questionKeys.releaseQuestionReservation(saga.userId, saga.quotaPeriodStartedAt, now)
            }
        }
        check(inbox.markSucceeded(claim, now)) {
            "Question translation Inbox claim was lost before terminal failure."
        }
    }

    companion object {
        const val CONSUMER_GROUP = "bs-backend-question-translation"
        const val RECOVERY_MIN_IDLE_TIME_MILLIS = 210_000L
        private val LEASE_DURATION: Duration = Duration.ofMinutes(3)
    }
}
