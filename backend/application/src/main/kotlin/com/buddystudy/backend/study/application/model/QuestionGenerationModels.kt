package com.buddystudy.backend.study.application.model

import java.time.Instant
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.study.domain.entity.QuestionEntity

enum class QuestionGenerationSource {
    MANUAL,
    SCHEDULED,
}

enum class QuestionGenerationStatus {
    QUEUED,
    GENERATING,
    TRANSLATING,
    COMPLETED,
    FAILED,
}

enum class QuestionGenerationStep {
    QUEUED,
    GENERATING,
    TRANSLATING,
    COMPLETED,
}

data class QuestionGenerationSaga(
    val correlationId: String,
    val userId: Long,
    val studyId: Long,
    val topicId: Long,
    val questionId: Long?,
    val source: QuestionGenerationSource,
    val status: QuestionGenerationStatus,
    val currentStep: QuestionGenerationStep,
    val idempotencyKey: String,
    val quotaPeriodStartedAt: Instant,
    val quotaRefundedAt: Instant?,
    val failedStep: QuestionGenerationStep?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
)

data class QuestionGenerationRequestedEvent(
    val eventId: String,
    val correlationId: String,
    val causationId: String? = null,
    val eventType: String = EVENT_TYPE,
    val eventVersion: Int = 1,
    val userId: Long,
    val studyId: Long,
    val topicId: Long,
    val source: QuestionGenerationSource,
    val occurredAt: Instant,
) {
    companion object {
        const val EVENT_TYPE = "QUESTION_GENERATION_REQUESTED"
    }
}

data class QuestionGenerationAcceptedResponse(
    val correlationId: String,
    val studyId: String,
    val topicId: String,
    val status: QuestionGenerationStatus,
    val pollAfterMs: Long = 250,
    val submittedAt: Instant,
)

data class QuestionGenerationErrorResponse(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

data class QuestionGenerationProcessResponse(
    val correlationId: String,
    val status: QuestionGenerationStatus,
    val currentStep: QuestionGenerationStep,
    val terminal: Boolean,
    val pollAfterMs: Long?,
    val questionId: String?,
    val question: StudyRecordResponse?,
    val failedStep: QuestionGenerationStep?,
    val error: QuestionGenerationErrorResponse?,
    val updatedAt: Instant,
    val completedAt: Instant?,
)

data class StreamInboxClaim(
    val eventId: String,
    val consumerGroup: String,
    val claimToken: String,
    val attempt: Int,
)

data class QueuedQuestionGeneration(
    val accepted: QuestionGenerationAcceptedResponse,
    val outboxes: List<OutboxReference>,
)

data class ClaimedQuestionGeneration(
    val saga: QuestionGenerationSaga,
    val inbox: StreamInboxClaim,
)

data class PreparedQuestionGeneration(
    val question: QuestionEntity,
    val embedding: List<Float>,
    val coverage: QuestionCoverageSelection?,
    val questionKey: OpenAIQuestionKey,
)

data class ClaimedQuestionTranslation(
    val saga: QuestionGenerationSaga,
    val inbox: StreamInboxClaim,
)
