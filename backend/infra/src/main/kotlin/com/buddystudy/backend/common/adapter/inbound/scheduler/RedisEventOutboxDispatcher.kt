package com.buddystudy.backend.common.adapter.inbound.scheduler

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.common.application.outbox.ClaimedRedisOutboxEvent
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxPort
import com.buddystudy.backend.config.BuddyStudyProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class RedisEventOutboxDispatcher(
    private val properties: BuddyStudyProperties,
    private val outbox: RedisEventOutboxPort,
    private val publisher: RedisStreamPublishOperations,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun dispatchBatch(now: Instant = Instant.now()): Int {
        if (!properties.streams.enabled) return 0
        val claimed = outbox.claimBatch(
            now = now,
            staleBefore = now.minus(CLAIM_LEASE),
            limit = BATCH_SIZE,
        )
        claimed.forEach { dispatch(it, now) }
        return claimed.size
    }

    private suspend fun dispatch(event: ClaimedRedisOutboxEvent, now: Instant) {
        val published = runCatching {
            require(event.payloadVersion == SUPPORTED_PAYLOAD_VERSION) {
                "Unsupported outbox payload version: ${event.payloadVersion}"
            }
            publisher.publish(
                properties.streams.key,
                mapOf(
                    "eventId" to event.eventId,
                    "eventType" to event.eventType.name,
                    "payload" to event.payloadJson,
                ),
            )
        }
        published.onSuccess { message ->
            outbox.markPublished(event.id, now)
            log.info(
                "redis_outbox_published outboxId={} eventId={} eventType={} redisRecordId={} attempts={} ageMs={}",
                event.id,
                event.eventId,
                event.eventType,
                message.recordId,
                event.attempts,
                Duration.between(event.createdAt, now).toMillis(),
            )
        }.onFailure { error ->
            val attempts = event.attempts + 1
            val nextAttemptAt = now.plusSeconds(retryDelaySeconds(attempts))
            outbox.markRetry(
                id = event.id,
                attempts = attempts,
                nextAttemptAt = nextAttemptAt,
                error = error.message ?: error.javaClass.simpleName,
                updatedAt = now,
            )
            log.warn(
                "redis_outbox_retry_scheduled outboxId={} eventId={} eventType={} attempts={} nextAttemptAt={} error={}",
                event.id,
                event.eventId,
                event.eventType,
                attempts,
                nextAttemptAt,
                error.message,
            )
        }
    }

    private fun retryDelaySeconds(attempts: Int): Long =
        (1L shl attempts.coerceIn(0, MAX_BACKOFF_EXPONENT))
            .coerceAtMost(MAX_RETRY_DELAY_SECONDS)

    private companion object {
        const val BATCH_SIZE = 100
        const val SUPPORTED_PAYLOAD_VERSION = 1
        const val MAX_BACKOFF_EXPONENT = 8
        const val MAX_RETRY_DELAY_SECONDS = 300L
        val CLAIM_LEASE: Duration = Duration.ofMinutes(2)
    }
}

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class RedisEventOutboxScheduler(
    private val dispatcher: RedisEventOutboxDispatcher,
) {
    @Scheduled(fixedDelayString = "\${buddystudy.outbox.poll-ms:1000}")
    suspend fun dispatchPendingEvents() {
        dispatcher.dispatchBatch()
    }
}
