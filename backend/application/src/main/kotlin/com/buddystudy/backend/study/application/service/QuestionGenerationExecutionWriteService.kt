package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.localization.application.port.ContentTranslationRequestAppendPort
import com.buddystudy.backend.study.application.model.ClaimedQuestionGeneration
import com.buddystudy.backend.study.application.model.PreparedQuestionGeneration
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.port.inbound.QuestionGenerationExecutionWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class QuestionGenerationExecutionWriteService(
    private val sagas: QuestionGenerationSagaPort,
    private val inbox: StreamInboxPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val questionEmbeddings: QuestionEmbeddingPort,
    private val questionCoverage: QuestionCoveragePort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val outbox: RedisEventOutboxAppendPort,
    private val translationRequests: ContentTranslationRequestAppendPort,
) : QuestionGenerationExecutionWriteUseCase {
    @Transactional
    override suspend fun claim(
        event: QuestionGenerationRequestedEvent,
        now: Instant,
        streamKey: String,
    ): ClaimedQuestionGeneration? {
        val inboxClaim = inbox.claim(
            eventId = event.eventId,
            consumerGroup = CONSUMER_GROUP,
            correlationId = event.correlationId,
            streamKey = streamKey,
            leaseDuration = LEASE_DURATION,
            now = now,
        ) ?: return null
        val saga = sagas.findByCorrelationId(event.correlationId)
        if (saga == null) {
            check(inbox.markSucceeded(inboxClaim, now)) { "Failed to close an orphaned generation Inbox claim." }
            return null
        }
        when (saga.status) {
            QuestionGenerationStatus.QUEUED ->
                check(sagas.markGenerating(saga.correlationId, now)) {
                    "Question generation Saga did not enter GENERATING."
                }
            QuestionGenerationStatus.GENERATING -> Unit
            else -> {
                check(inbox.markSucceeded(inboxClaim, now)) {
                    "Failed to close a terminal generation Inbox claim."
                }
                return null
            }
        }
        return ClaimedQuestionGeneration(
            saga = sagas.findByCorrelationId(event.correlationId)
                ?: error("Question generation Saga disappeared after claim."),
            inbox = inboxClaim,
        )
    }

    @Transactional
    override suspend fun complete(
        event: QuestionGenerationRequestedEvent,
        claim: StreamInboxClaim,
        prepared: PreparedQuestionGeneration,
        now: Instant,
    ): QuestionWriteResult {
        val saved = questions.save(prepared.question)
        questionStats.save(QuestionStatsEntity(questionId = saved.id, updatedAt = now))
        prepared.coverage?.let { questionCoverage.markAsked(it, now) }
        questionEmbeddings.save(
            questionId = saved.id,
            userId = checkNotNull(saved.userId) { "Created question must have a user." },
            studyId = checkNotNull(saved.studyId) { "Created question must have a study." },
            topic = saved.topic,
            question = saved.question,
            embedding = prepared.embedding,
        )
        questionKeys.markQuestionCreated(prepared.questionKey, now)
        check(sagas.markTranslating(event.correlationId, saved.id, now)) {
            "Question generation Saga did not enter TRANSLATING."
        }
        val generatedEvent = QuestionGeneratedEvent(
            eventId = UUID.randomUUID().toString(),
            correlationId = event.correlationId,
            causationId = event.eventId,
            questionId = saved.id,
            userId = event.userId,
            studyId = event.studyId,
            topicId = event.topicId,
            source = event.source,
            sourceLanguage = saved.sourceLanguage.databaseValue,
            generatedAt = now,
            occurredAt = now,
        )
        val outboxId = outbox.appendQuestionGenerated(generatedEvent, now)
        val translationOutboxes = translationRequests.appendRecordForSupportedLanguages(saved, now)
        check(inbox.markSucceeded(claim, now)) {
            "Question generation Inbox claim was lost before completion."
        }
        return QuestionWriteResult(
            question = saved,
            outboxes = listOf(OutboxReference(OutboxType.DOMAIN_EVENT, outboxId)) + translationOutboxes,
        )
    }

    @Transactional
    override suspend fun retry(claim: StreamInboxClaim, error: String, now: Instant) {
        check(inbox.releaseForRetry(claim, "QUESTION_GENERATION_FAILED", error, now)) {
            "Question generation Inbox claim was lost before retry."
        }
    }

    @Transactional
    override suspend fun fail(
        event: QuestionGenerationRequestedEvent,
        claim: StreamInboxClaim,
        errorCode: String,
        errorMessage: String,
        now: Instant,
    ) {
        val saga = sagas.findByCorrelationId(event.correlationId)
        if (saga != null && saga.status !in setOf(QuestionGenerationStatus.COMPLETED, QuestionGenerationStatus.FAILED)) {
            check(
                sagas.markFailed(
                    correlationId = saga.correlationId,
                    failedStep = QuestionGenerationStep.GENERATING,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                    refundedAt = now,
                    now = now,
                ),
            ) { "Question generation Saga did not enter FAILED." }
            if (saga.quotaRefundedAt == null) {
                questionKeys.releaseQuestionReservation(saga.userId, saga.quotaPeriodStartedAt, now)
            }
        }
        check(inbox.markFailed(claim, errorCode, errorMessage, now)) {
            "Question generation Inbox claim was lost before terminal failure."
        }
    }

    companion object {
        const val CONSUMER_GROUP = "bs-backend-question-generation"
        const val RECOVERY_MIN_IDLE_TIME_MILLIS = 210_000L
        private val LEASE_DURATION: Duration = Duration.ofMinutes(3)
    }
}
