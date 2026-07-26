package com.buddystudy.backend.common.adapter.inbound.scheduler

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishedMessage
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.application.outbox.ClaimedRedisOutboxEvent
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxPort
import com.buddystudy.backend.common.application.outbox.RedisOutboxEventType
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class RedisEventOutboxDispatcherTest {
    @Test
    fun `publishes claimed event with its idempotency key and marks it published`(): Unit = runBlocking {
        val now = Instant.parse("2026-07-23T00:00:00Z")
        val event = claimedEvent(createdAt = now.minusSeconds(10))
        val outbox = CapturingOutbox(mutableListOf(event))
        val publisher = CapturingPublisher()
        val dispatcher = RedisEventOutboxDispatcher(enabledProperties(), outbox, publisher)

        assertThat(dispatcher.dispatchBatch(now)).isEqualTo(1)

        assertThat(publisher.fields).containsExactly(
            mapOf(
                "eventId" to event.eventId,
                "eventType" to event.eventType.name,
                "payload" to event.payloadJson,
            ),
        )
        assertThat(publisher.topics).containsExactly(RedisStreamTopic.DOMAIN_EVENTS)
        assertThat(outbox.published).containsExactly(event.id to now)
        assertThat(outbox.retries).isEmpty()
    }

    @Test
    fun `failed publish is returned to pending with bounded exponential retry`(): Unit = runBlocking {
        val now = Instant.parse("2026-07-23T00:00:00Z")
        val event = claimedEvent(attempts = 1)
        val outbox = CapturingOutbox(mutableListOf(event))
        val dispatcher = RedisEventOutboxDispatcher(
            enabledProperties(),
            outbox,
            CapturingPublisher(failure = IllegalStateException("redis unavailable")),
        )

        assertThat(dispatcher.dispatchBatch(now)).isEqualTo(1)

        assertThat(outbox.published).isEmpty()
        assertThat(outbox.retries).containsExactly(
            RetryCall(
                id = event.id,
                attempts = 2,
                nextAttemptAt = now.plusSeconds(4),
                error = "redis unavailable",
                updatedAt = now,
            ),
        )
    }

    @Test
    fun `disabled streams leave outbox untouched`(): Unit = runBlocking {
        val outbox = CapturingOutbox(mutableListOf(claimedEvent()))
        val properties = BuddyStudyProperties(
            streams = BuddyStudyProperties.Streams(enabled = false),
        )
        val dispatcher = RedisEventOutboxDispatcher(properties, outbox, CapturingPublisher())

        assertThat(dispatcher.dispatchBatch()).isZero()
        assertThat(outbox.claimCalls).isZero()
    }

    private fun enabledProperties() = BuddyStudyProperties(
        streams = BuddyStudyProperties.Streams(enabled = true, key = "events"),
    )

    private fun claimedEvent(
        attempts: Int = 0,
        createdAt: Instant = Instant.parse("2026-07-22T23:59:00Z"),
    ) = ClaimedRedisOutboxEvent(
        id = 7,
        eventId = "notification-requested-42",
        eventType = RedisOutboxEventType.NOTIFICATION_REQUESTED,
        payloadVersion = 1,
        payloadJson = """{"questionId":42}""",
        attempts = attempts,
        createdAt = createdAt,
    )

    private class CapturingPublisher(
        private val failure: Throwable? = null,
    ) : RedisStreamPublishOperations {
        val fields = mutableListOf<Map<String, String>>()
        val topics = mutableListOf<RedisStreamTopic>()

        override suspend fun publish(
            topic: RedisStreamTopic,
            fields: Map<String, String>,
        ): RedisStreamPublishedMessage {
            failure?.let { throw it }
            topics += topic
            this.fields += fields
            return RedisStreamPublishedMessage(topic.apiName, "1-0")
        }
    }

    private class CapturingOutbox(
        private val claimed: MutableList<ClaimedRedisOutboxEvent>,
    ) : RedisEventOutboxPort {
        var claimCalls = 0
        val published = mutableListOf<Pair<Long, Instant>>()
        val retries = mutableListOf<RetryCall>()

        override suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant): Long =
            error("unused")

        override suspend fun claimBatch(
            now: Instant,
            staleBefore: Instant,
            limit: Int,
        ): List<ClaimedRedisOutboxEvent> {
            claimCalls += 1
            return claimed.toList().also { claimed.clear() }
        }

        override suspend fun markPublished(id: Long, publishedAt: Instant): Boolean {
            published += id to publishedAt
            return true
        }

        override suspend fun markRetry(
            id: Long,
            attempts: Int,
            nextAttemptAt: Instant,
            error: String,
            updatedAt: Instant,
        ): Boolean {
            retries += RetryCall(id, attempts, nextAttemptAt, error, updatedAt)
            return true
        }
    }

    private data class RetryCall(
        val id: Long,
        val attempts: Int,
        val nextAttemptAt: Instant,
        val error: String,
        val updatedAt: Instant,
    )
}
