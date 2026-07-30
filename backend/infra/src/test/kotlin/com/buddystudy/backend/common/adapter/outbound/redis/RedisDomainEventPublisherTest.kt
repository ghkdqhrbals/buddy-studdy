package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.common.application.outbox.ClaimedRedisOutboxEvent
import com.buddystudy.backend.common.application.outbox.RedisOutboxEventType
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisDomainEventPublisherTest {
    @Test
    fun `question generated events are published to the translation topic`() = runBlocking {
        val streams = RecordingPublisher()
        val publisher = RedisDomainEventPublisher(streams)

        publisher.publish(event(RedisOutboxEventType.QUESTION_GENERATED))

        assertThat(streams.topic).isEqualTo(RedisStreamTopic.STUDY_QUESTION_GENERATED)
    }

    @Test
    fun `answer grading events use their dedicated topic`() = runBlocking {
        val streams = RecordingPublisher()
        val publisher = RedisDomainEventPublisher(streams)

        publisher.publish(event(RedisOutboxEventType.ANSWER_GRADING_REQUESTED))

        assertThat(streams.topic).isEqualTo(RedisStreamTopic.STUDY_ANSWER_GRADING_REQUESTED)
    }

    @Test
    fun `every community event uses its dedicated topic`() = runBlocking {
        val expected = mapOf(
            RedisOutboxEventType.CONTENT_VIEWED to RedisStreamTopic.COMMUNITY_QUESTION_VIEWED,
            RedisOutboxEventType.QUESTION_LIKED to RedisStreamTopic.COMMUNITY_QUESTION_LIKED,
            RedisOutboxEventType.QUESTION_UNLIKED to RedisStreamTopic.COMMUNITY_QUESTION_UNLIKED,
            RedisOutboxEventType.QUESTION_COMMENTED to RedisStreamTopic.COMMUNITY_QUESTION_COMMENTED,
            RedisOutboxEventType.QUESTION_COMMENT_DELETED to RedisStreamTopic.COMMUNITY_QUESTION_COMMENT_DELETED,
        )

        expected.forEach { (eventType, topic) ->
            val streams = RecordingPublisher()
            RedisDomainEventPublisher(streams).publish(event(eventType))
            assertThat(streams.topic).isEqualTo(topic)
        }
    }

    private fun event(type: RedisOutboxEventType) =
        ClaimedRedisOutboxEvent(
            id = 1,
            eventId = "event-1",
            eventType = type,
            payloadVersion = 1,
            payloadJson = "{}",
            attempts = 0,
            createdAt = Instant.parse("2026-07-27T00:00:00Z"),
            claimToken = "claim-1",
        )

    private class RecordingPublisher : RedisStreamPublishOperations {
        var topic: RedisStreamTopic? = null

        override suspend fun publish(
            topic: RedisStreamTopic,
            fields: Map<String, String>,
        ): RedisStreamPublishedMessage {
            this.topic = topic
            return RedisStreamPublishedMessage("stream", "1-0")
        }
    }
}
