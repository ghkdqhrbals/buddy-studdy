package com.buddystudy.backend.externalapi.application.model

import java.time.Instant

data class StartExternalApiCallCommand(
    val callId: String,
    val correlationId: String?,
    val provider: String,
    val operation: String,
    val httpMethod: String,
    val requestUrl: String,
    val requestHeadersJson: String,
    val requestBody: String?,
    val startedAt: Instant,
)

data class FinishExternalApiCallCommand(
    val callId: String,
    val status: String,
    val responseStatus: Int?,
    val responseHeadersJson: String?,
    val responseBody: String?,
    val errorType: String?,
    val errorMessage: String?,
    val finishedAt: Instant,
)

data class ExternalApiHistoryQuery(
    val cursor: Long?,
    val limit: Int,
    val provider: String?,
    val status: String?,
    val query: String?,
)

data class ExternalApiHistoryPage(
    val items: List<ExternalApiCallHistorySummary>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val limit: Int,
)

data class ExternalApiCallHistorySummary(
    val id: Long,
    val callId: String,
    val correlationId: String?,
    val provider: String,
    val operation: String,
    val httpMethod: String,
    val requestUrl: String,
    val responseStatus: Int?,
    val status: String,
    val errorType: String?,
    val errorMessage: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val durationMs: Long?,
)

data class ExternalApiCallHistory(
    val id: Long,
    val callId: String,
    val correlationId: String?,
    val provider: String,
    val operation: String,
    val httpMethod: String,
    val requestUrl: String,
    val requestHeadersJson: String,
    val requestBody: String?,
    val responseStatus: Int?,
    val responseHeadersJson: String?,
    val responseBody: String?,
    val status: String,
    val errorType: String?,
    val errorMessage: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val durationMs: Long?,
)
