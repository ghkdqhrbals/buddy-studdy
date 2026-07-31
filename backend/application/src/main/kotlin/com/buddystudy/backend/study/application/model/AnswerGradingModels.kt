package com.buddystudy.backend.study.application.model

import java.time.Instant

typealias AnswerGradingStatus = com.buddystudy.study.domain.entity.AnswerGradingStatus

data class AnswerGradingRequestedEvent(
    val eventId: String,
    val requestId: String,
    val recordId: Long,
    val userId: Long,
    val requestedAt: Instant,
    val responseLanguage: String = "ko",
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
    val correlationId: String,
    val status: AnswerGradingStatus,
    val errorMessage: String? = null,
    val occurredAt: Instant,
)

data class AnswerGradingProcessResponse(
    val correlationId: String,
    val recordId: String,
    val status: AnswerGradingStatus,
    val terminal: Boolean,
    val pollAfterMs: Long?,
    val events: List<AnswerGradingProgressResponse>,
    val errorMessage: String? = null,
    val updatedAt: Instant,
)

fun AnswerGradingProgress.toResponse() = AnswerGradingProgressResponse(
    id = id,
    recordId = recordId.toString(),
    correlationId = requestId,
    status = status,
    errorMessage = errorMessage,
    occurredAt = occurredAt,
)
