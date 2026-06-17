package com.buddystuddy.backend

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.notification.adapter.outbound.stream.NotificationRedisStreamPublisher
import com.buddystuddy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystuddy.backend.study.adapter.outbound.stream.RedisStreamQuestionCreatedPublisher
import com.redisstream.consumer.ProducerRoutingShard
import com.redisstream.producer.ProducerRoute
import com.redisstream.producer.PublishedRedisStreamMessage
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.util.stream.Stream

class RedisStreamStudySchedulingPublishersTest {
    @Test
    fun `question created events publish without partition key`() {
        val publisher = RecordingPublisher()
        val service = RedisStreamQuestionCreatedPublisher(
            properties = BuddyStuddyProperties().apply { streams.enabled = true },
            publisherProvider = provider(publisher),
        )

        assertThat(service.publishQuestionCreated(33, "ko", Instant.parse("2026-06-17T00:00:00Z"))).isTrue()

        val request = publisher.requests.single()
        assertThat(request.key).isNull()
        assertThat(request.fields).containsEntry("eventType", "QUESTION_CREATED")
        assertThat(request.fields["payload"]).contains("\"questionId\":33")
    }

    @Test
    fun `notification events publish without partition key`() {
        val publisher = RecordingPublisher()
        val service = NotificationRedisStreamPublisher(
            properties = BuddyStuddyProperties().apply { streams.enabled = true },
            publisherProvider = provider(publisher),
        )

        assertThat(
            service.publishNotification(
                NotificationRequestCommand(
                    eventId = "event-1",
                    userId = 7,
                    title = "BuddyStuddy",
                    body = "Question ready",
                    type = "STUDY_QUESTION",
                    shouldPush = true,
                )
            )
        ).isTrue()

        val request = publisher.requests.single()
        assertThat(request.key).isNull()
        assertThat(request.fields).containsEntry("eventType", "NOTIFICATION_REQUESTED")
        assertThat(request.fields).containsEntry("eventId", "event-1")
    }

    private fun provider(publisher: RedisStreamPublisher?): ObjectProvider<RedisStreamPublisher> =
        object : ObjectProvider<RedisStreamPublisher> {
            override fun getObject(): RedisStreamPublisher =
                publisher ?: throw NoSuchElementException("No publisher")

            override fun getIfAvailable(): RedisStreamPublisher? = publisher
            override fun iterator(): MutableIterator<RedisStreamPublisher> = listOfNotNull(publisher).toMutableList().iterator()
            override fun stream(): Stream<RedisStreamPublisher> = listOfNotNull(publisher).stream()
        }

    private data class PublishRequest(
        val key: String?,
        val fields: Map<String, String>,
        val options: RedisStreamPublishOptions,
    )

    private class RecordingPublisher : RedisStreamPublisher {
        val requests = mutableListOf<PublishRequest>()

        override fun publish(
            partitionKey: String?,
            fields: Map<String, String>,
            options: RedisStreamPublishOptions,
        ): PublishedRedisStreamMessage {
            requests += PublishRequest(partitionKey, fields, options)
            val streamKey = "stream-${partitionKey ?: "load"}"
            return PublishedRedisStreamMessage(
                streamKey,
                "record-1",
                ProducerRoute(streamKey, ProducerRoutingShard(0, streamKey, 0), 1),
            )
        }
    }
}
