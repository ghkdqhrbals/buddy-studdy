package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.model.AnswerGradingStatus
import com.buddystudy.backend.study.application.model.GeneratedQuestionWithEmbedding
import com.buddystudy.backend.study.application.model.QueuedQuestionGeneration
import com.buddystudy.backend.study.application.model.ClaimedQuestionGeneration
import com.buddystudy.backend.study.application.model.PreparedQuestionGeneration
import com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.model.ClaimedQuestionTranslation
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyEntity
import java.time.Instant

data class QuestionWriteResult(
    val question: QuestionEntity,
    val outboxes: List<OutboxReference>,
)

interface QuestionCreationWriteUseCase {
    suspend fun saveQuestionWithOutboxes(
        question: QuestionEntity,
        embedding: List<Float>,
        coverage: QuestionCoverageSelection?,
        questionKey: OpenAIQuestionKey,
        now: Instant,
    ): QuestionWriteResult
}

interface QuestionGenerationRequestWriteUseCase {
    suspend fun enqueueManual(
        userId: Long,
        deviceId: String,
        studyId: Long,
        idempotencyKey: String,
        now: Instant,
    ): QueuedQuestionGeneration

    suspend fun enqueueScheduled(
        scheduleStudy: StudyEntity,
        topicStudy: StudyEntity,
        idempotencyKey: String,
        now: Instant,
    ): QueuedQuestionGeneration
}

interface QuestionGenerationExecutionWriteUseCase {
    suspend fun claim(
        event: QuestionGenerationRequestedEvent,
        now: Instant,
        streamKey: String = "test",
    ): ClaimedQuestionGeneration?

    suspend fun complete(
        event: QuestionGenerationRequestedEvent,
        claim: StreamInboxClaim,
        prepared: PreparedQuestionGeneration,
        now: Instant,
    ): QuestionWriteResult

    suspend fun retry(claim: StreamInboxClaim, error: String, now: Instant)

    suspend fun fail(
        event: QuestionGenerationRequestedEvent,
        claim: StreamInboxClaim,
        errorCode: String,
        errorMessage: String,
        now: Instant,
    )
}

interface QuestionDeliveryWriteUseCase {
    suspend fun enqueue(
        question: QuestionEntity,
        rootStudy: StudyEntity,
        appLanguage: String,
        now: Instant,
    ): QuestionWriteResult
}

interface QuestionTranslationExecutionWriteUseCase {
    suspend fun claim(
        event: QuestionGeneratedEvent,
        now: Instant,
        streamKey: String = "test",
    ): ClaimedQuestionTranslation?

    suspend fun complete(
        event: QuestionGeneratedEvent,
        claim: StreamInboxClaim,
        translation: TranslatedQuestionContent?,
        rootStudy: StudyEntity,
        appLanguage: String,
        now: Instant,
    ): QuestionWriteResult

    suspend fun retry(claim: StreamInboxClaim, error: String, now: Instant)

    suspend fun fail(
        event: QuestionGeneratedEvent,
        claim: StreamInboxClaim,
        errorMessage: String,
        now: Instant,
    )
}

interface ScheduledQuestionWriteUseCase {
    suspend fun complete(
        scheduleStudy: StudyEntity,
        topicStudy: StudyEntity,
        generated: GeneratedQuestionWithEmbedding,
        coverage: QuestionCoverageSelection?,
        questionKey: OpenAIQuestionKey,
        appLanguage: String,
        now: Instant,
    ): QuestionWriteResult

    suspend fun deferUntilNextInterval(study: StudyEntity, now: Instant)

    suspend fun fail(
        study: StudyEntity,
        questionKey: OpenAIQuestionKey?,
        error: String,
        retryAt: Instant,
        now: Instant,
    )
}

interface StudyRecordWriteUseCase {
    suspend fun answer(
        userId: Long,
        recordId: Long,
        answer: String,
        sourceLanguage: String,
        grade: GradedAnswer?,
        now: Instant,
    ): QuestionEntity

    suspend fun skip(userId: Long, recordId: Long): QuestionEntity
    suspend fun delete(userId: Long, recordId: Long, now: Instant)
    suspend fun clear(userId: Long, now: Instant)
    suspend fun updatePublicity(userId: Long, recordId: Long, isPublic: Boolean): QuestionEntity
}

data class QueuedAnswerGrading(
    val question: QuestionEntity,
    val outboxes: List<OutboxReference>,
)

interface AnswerGradingWriteUseCase {
    suspend fun queue(
        userId: Long,
        recordId: Long,
        answer: String,
        sourceLanguage: String,
        aiResponseLanguage: String,
        now: Instant,
    ): QueuedAnswerGrading

    suspend fun transition(
        event: AnswerGradingRequestedEvent,
        status: AnswerGradingStatus,
        now: Instant,
    ): Boolean

    suspend fun complete(
        event: AnswerGradingRequestedEvent,
        grade: GradedAnswer,
        now: Instant,
    ): Boolean

    suspend fun fail(
        event: AnswerGradingRequestedEvent,
        errorMessage: String,
        now: Instant,
    )
}
