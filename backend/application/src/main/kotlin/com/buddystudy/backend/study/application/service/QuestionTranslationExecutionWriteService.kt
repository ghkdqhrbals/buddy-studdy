package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.study.application.model.ClaimedQuestionTranslation
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.model.toRollbackRequestedEvent
import com.buddystudy.backend.study.application.port.inbound.QuestionTranslationExecutionWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.StudyEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class QuestionTranslationExecutionWriteService(
    private val sagas: QuestionGenerationSagaPort,
    private val inbox: StreamInboxPort,
    private val questions: QuestionPort,
    private val localizations: ContentLocalizationPort,
    private val notificationOutbox: RedisEventOutboxAppendPort,
) : QuestionTranslationExecutionWriteUseCase {
    @Transactional
    override suspend fun claim(
        event: QuestionGeneratedEvent,
        now: Instant,
        streamKey: String,
    ): ClaimedQuestionTranslation? {
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
        val question = checkNotNull(questions.findQuestionById(event.questionId)) {
            "Question disappeared before delivery."
        }
        if (translation != null) {
            val targetLanguage = QuestionLanguage.normalize(appLanguage)
            val hashes = ContentSourceHashPolicy.recordHashes(question)
            localizations.ensureRecordPending(
                question,
                targetLanguage,
                hashes,
                now,
                now.minus(Duration.ofMinutes(5)),
            )
            check(
                localizations.saveQuestionReady(
                    question = question,
                    targetLanguage = targetLanguage,
                    sourceHash = hashes.question,
                    result = ContentTranslationResult(
                        fields = mapOf(
                            "topic" to translation.topic,
                            "question" to translation.question,
                            "hint" to translation.hint,
                        ),
                        provider = "question-generation",
                    ),
                    now = now,
                ),
            ) { "Question disappeared while saving its translation." }
            question.topic = translation.topic
            question.question = translation.question
            question.hint = translation.hint
        }
        val notificationId = notificationOutbox.appendNotification(
            question.toQuestionNotification(rootStudy, appLanguage),
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
            outboxes = listOf(OutboxReference(OutboxType.DOMAIN_EVENT, notificationId)),
        )
    }

    @Transactional
    override suspend fun retry(claim: StreamInboxClaim, error: String, now: Instant) {
        check(inbox.releaseForRetry(claim, "QUESTION_TRANSLATION_FAILED", error, now)) {
            "Question translation Inbox claim was lost before retry."
        }
    }

    @Transactional
    override suspend fun fail(
        event: QuestionGeneratedEvent,
        claim: StreamInboxClaim,
        errorMessage: String,
        now: Instant,
    ): OutboxReference? {
        val saga = sagas.findByCorrelationId(event.correlationId)
        var rollbackOutbox: OutboxReference? = null
        if (saga != null && saga.status !in setOf(QuestionGenerationStatus.COMPLETED, QuestionGenerationStatus.FAILED)) {
            check(
                sagas.markFailed(
                    correlationId = saga.correlationId,
                    failedStep = QuestionGenerationStep.TRANSLATING,
                    errorCode = "QUESTION_TRANSLATION_FAILED",
                    errorMessage = "질문 번역을 완료하지 못했습니다.",
                    refundedAt = null,
                    now = now,
                ),
            ) { "Question generation Saga did not enter FAILED during translation." }
            rollbackOutbox = OutboxReference(
                OutboxType.DOMAIN_EVENT,
                notificationOutbox.appendQuestionGenerationRollbackRequested(
                    saga.toRollbackRequestedEvent(event.eventId, QuestionGenerationStep.TRANSLATING, now),
                    now,
                ),
            )
        }
        check(inbox.markFailed(claim, "QUESTION_TRANSLATION_FAILED", errorMessage, now)) {
            "Question translation Inbox claim was lost before terminal failure."
        }
        return rollbackOutbox
    }

    companion object {
        const val CONSUMER_GROUP = "bs-backend-question-translation"
        const val RECOVERY_MIN_IDLE_TIME_MILLIS = 210_000L
        private val LEASE_DURATION: Duration = Duration.ofMinutes(3)
    }
}
