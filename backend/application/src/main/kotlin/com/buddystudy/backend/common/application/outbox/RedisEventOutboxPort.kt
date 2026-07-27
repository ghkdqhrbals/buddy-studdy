package com.buddystudy.backend.common.application.outbox

import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import java.time.Instant

enum class OutboxType {
    DOMAIN_EVENT,
    QUESTION_PUSH,
}

data class OutboxReference(
    val type: OutboxType,
    val id: Long,
)

data class OutboxPublishSummary(
    val attempted: Int,
    val published: Int,
    val retryScheduled: Int,
)

interface PublishOutboxUseCase {
    suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary
}

interface RecoverOutboxUseCase {
    suspend fun recoverPending(): OutboxPublishSummary
}

interface DomainEventPublishPort {
    suspend fun publish(event: ClaimedRedisOutboxEvent): String
}

interface AfterCommitPort {
    suspend fun execute(action: suspend () -> Unit)
}

enum class RedisOutboxEventType {
    NOTIFICATION_REQUESTED,
    ACCOUNT_WITHDRAWN,
}

data class ClaimedRedisOutboxEvent(
    val id: Long,
    val eventId: String,
    val eventType: RedisOutboxEventType,
    val payloadVersion: Int,
    val payloadJson: String,
    val attempts: Int,
    val createdAt: Instant,
    val claimToken: String,
)

interface RedisEventOutboxAppendPort {
    suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant = Instant.now()): Long
}

interface RedisEventOutboxPort : RedisEventOutboxAppendPort {
    suspend fun claim(id: Long, now: Instant, staleBefore: Instant): ClaimedRedisOutboxEvent?
    suspend fun claimBatch(now: Instant, staleBefore: Instant, limit: Int): List<ClaimedRedisOutboxEvent>
    suspend fun markPublished(id: Long, claimToken: String, publishedAt: Instant): Boolean
    suspend fun markRetry(
        id: Long,
        claimToken: String,
        attempts: Int,
        nextAttemptAt: Instant,
        error: String,
        updatedAt: Instant,
    ): Boolean
}
