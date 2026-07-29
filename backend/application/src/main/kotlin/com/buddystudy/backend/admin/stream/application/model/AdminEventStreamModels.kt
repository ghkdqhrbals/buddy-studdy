package com.buddystudy.backend.admin.stream.application.model

import java.time.Instant

data class AdminCursorPage<T>(
    val items: List<T>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val limit: Int,
)

data class AdminStreamTopicSummary(
    val topic: String,
    val streamKey: String,
    val maxLength: Long,
    val length: Long,
    val firstEntryId: String?,
    val lastEntryId: String?,
    val groups: List<AdminStreamGroupSummary>,
    val inspectionErrors: List<AdminStreamInspectionError> = emptyList(),
)

data class AdminStreamGroupSummary(
    val name: String,
    val consumers: Long,
    val pending: Long,
    val lastDeliveredId: String?,
    val entriesRead: Long?,
    val lag: Long?,
    val pendingMinId: String?,
    val pendingMaxId: String?,
    val oldestPendingIdleMs: Long?,
    val maxDeliveryCount: Long,
    val maxRetryCount: Long,
    val pendingSampleTruncated: Boolean,
    val consumerDetails: List<AdminStreamConsumerSummary>,
    val inspectionErrors: List<AdminStreamInspectionError> = emptyList(),
)

data class AdminStreamInspectionError(
    val operation: String,
    val message: String,
)

data class AdminStreamConsumerSummary(
    val name: String,
    val pending: Long,
    val idleMs: Long,
    val inactiveMs: Long?,
)

data class AdminStreamPendingEntry(
    val id: String,
    val consumer: String,
    val idleMs: Long,
    val deliveryCount: Long,
    val retryCount: Long,
)

data class AdminStreamInboxAttempt(
    val id: Long,
    val eventId: String,
    val consumerGroup: String,
    val correlationId: String,
    val streamKey: String,
    val attempt: Int,
    val status: String,
    val errorType: String?,
    val errorMessage: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val durationMs: Long?,
)

data class AdminStreamEntry(
    val id: String,
    val eventType: String?,
    val eventId: String?,
    val recordId: String?,
    val userId: String?,
    val deviceId: String?,
    val fields: Map<String, String>,
)

data class AdminRedisEventOutboxEntry(
    val id: Long,
    val eventId: String,
    val eventType: String,
    val streamKey: String?,
    val redisRecordId: String?,
    val payloadVersion: Int,
    val payloadJson: String,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val claimedAt: Instant?,
    val publishedAt: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminPushOutboxEntry(
    val id: Long,
    val recordId: Long,
    val deviceId: String,
    val userId: Long?,
    val studyId: Long?,
    val topic: String,
    val streamKey: String?,
    val redisRecordId: String?,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val publishedAt: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
