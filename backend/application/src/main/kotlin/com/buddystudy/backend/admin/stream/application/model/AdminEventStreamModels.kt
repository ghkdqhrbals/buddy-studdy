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
)

data class AdminStreamGroupSummary(
    val name: String,
    val consumers: Long,
    val pending: Long,
    val lastDeliveredId: String?,
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
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val publishedAt: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
