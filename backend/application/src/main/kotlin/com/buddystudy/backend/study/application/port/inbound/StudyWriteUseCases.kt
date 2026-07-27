package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.model.AnswerGradingStatus
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.study.application.model.GeneratedQuestionWithEmbedding
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
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
        notification: (QuestionEntity) -> NotificationRequestCommand,
        push: (QuestionEntity) -> QuestionPushRequest,
        now: Instant,
    ): QuestionWriteResult
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
        grade: GradedAnswer?,
        now: Instant,
    ): QuestionEntity

    suspend fun skip(userId: Long, recordId: Long): QuestionEntity
    suspend fun delete(userId: Long, recordId: Long, now: Instant)
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
