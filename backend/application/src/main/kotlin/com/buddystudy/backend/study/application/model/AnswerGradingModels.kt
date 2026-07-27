package com.buddystudy.backend.study.application.model

import java.time.Instant

enum class AnswerGradingStatus {
    QUEUED,
    ANALYZING_EVIDENCE,
    CRITIQUING,
    JUDGING,
    ADJUDICATING,
    COMPLETED,
    FAILED,
    ;

    val terminal: Boolean
        get() = this == COMPLETED || this == FAILED
}

data class AnswerGradingRequestedEvent(
    val eventId: String,
    val requestId: String,
    val recordId: Long,
    val userId: Long,
    val requestedAt: Instant,
)

data class AnswerGradingProgress(
    val id: Long,
    val recordId: Long,
    val requestId: String,
    val status: AnswerGradingStatus,
    val errorMessage: String? = null,
    val occurredAt: Instant,
)

data class AnswerGradingProgressResponse(
    val id: Long,
    val recordId: String,
    val requestId: String,
    val status: AnswerGradingStatus,
    val errorMessage: String? = null,
    val occurredAt: Instant,
)

fun AnswerGradingProgress.toResponse() = AnswerGradingProgressResponse(
    id = id,
    recordId = recordId.toString(),
    requestId = requestId,
    status = status,
    errorMessage = errorMessage,
    occurredAt = occurredAt,
)
