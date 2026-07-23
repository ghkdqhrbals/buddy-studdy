package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishedMessage
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.notification.adapter.outbound.stream.NotificationRedisStreamPublisher
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.study.adapter.outbound.stream.RedisStreamQuestionCreatedPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class RedisStreamStudySchedulingPublishersTest {
    @Test
    fun `question created events publish to configured stream`(): Unit = runBlocking {
        val publisher = RecordingPublisher()
        val service = RedisStreamQuestionCreatedPublisher(
            properties = BuddyStudyProperties().apply {
                streams.enabled = true
                streams.key = "buddystudy-events-v1"
            },
            publisher = publisher,
        )

        assertThat(service.publishQuestionCreated(33, "ko", Instant.parse("2026-06-17T00:00:00Z"))).isTrue()

        val request = publisher.requests.single()
        assertThat(request.streamKey).isEqualTo("buddystudy-events-v1")
        assertThat(request.fields).containsEntry("eventType", "QUESTION_CREATED")
        assertThat(request.fields["payload"]).contains("\"questionId\":33")
    }

    @Test
    fun `notification events publish to configured stream`(): Unit = runBlocking {
        val publisher = RecordingPublisher()
        val service = NotificationRedisStreamPublisher(
            properties = BuddyStudyProperties().apply {
                streams.enabled = true
                streams.key = "buddystudy-events-v1"
            },
            publisher = publisher,
        )

        assertThat(
            service.publishNotification(
                NotificationRequestCommand(
                    eventId = "event-1",
                    userId = 7,
                    title = "BuddyStudy",
                    body = "Question ready",
                    type = "STUDY_QUESTION",
                    shouldPush = true,
                )
            )
        ).isTrue()

        val request = publisher.requests.single()
        assertThat(request.streamKey).isEqualTo("buddystudy-events-v1")
        assertThat(request.fields).containsEntry("eventType", "NOTIFICATION_REQUESTED")
        assertThat(request.fields).containsEntry("eventId", "event-1")
    }

    private data class PublishRequest(val streamKey: String, val fields: Map<String, String>)

    private class RecordingPublisher : RedisStreamPublishOperations {
        val requests = mutableListOf<PublishRequest>()

        override suspend fun publish(streamKey: String, fields: Map<String, String>): RedisStreamPublishedMessage {
            requests += PublishRequest(streamKey, fields)
            return RedisStreamPublishedMessage(streamKey, "record-1")
        }
    }
}
