package com.buddystudy.backend.common.application.outbox

import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import java.time.Instant

enum class RedisOutboxEventType {
    NOTIFICATION_REQUESTED,
}

data class ClaimedRedisOutboxEvent(
    val id: Long,
    val eventId: String,
    val eventType: RedisOutboxEventType,
    val payloadVersion: Int,
    val payloadJson: String,
    val attempts: Int,
    val createdAt: Instant,
)

interface RedisEventOutboxPort {
    suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant = Instant.now()): Long
    suspend fun claimBatch(now: Instant, staleBefore: Instant, limit: Int): List<ClaimedRedisOutboxEvent>
    suspend fun markPublished(id: Long, publishedAt: Instant): Boolean
    suspend fun markRetry(id: Long, attempts: Int, nextAttemptAt: Instant, error: String, updatedAt: Instant): Boolean
}
