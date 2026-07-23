package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishedMessage
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.adapter.outbound.stream.RedisStreamPushPublisher
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class RedisStreamPushPublisherTest {
    @Test
    fun `publish methods return false when streams are disabled`(): Unit = runBlocking {
        val publisher = RecordingPublisher()
        val service = service(enabled = false, publisher = publisher)

        assertThat(service.publishPush(pushEvent())).isFalse()
        assertThat(publisher.requests).isEmpty()
    }

    @Test
    fun `push event publishes to configured stream`(): Unit = runBlocking {
        val publisher = RecordingPublisher()
        val service = service(enabled = true, publisher = publisher)

        assertThat(service.publishPush(pushEvent(topic = "SwiftUI"))).isTrue()

        val request = publisher.requests.single()
        assertThat(request.streamKey).isEqualTo("buddystudy-events-v1")
        assertThat(request.fields).containsEntry("eventType", "QUESTION_PUSH_REQUESTED")
        assertThat(request.fields).containsKey("payload")
        assertThat(request.fields["payload"])
            .contains("\"recordId\":10")
            .contains("\"studyId\":77")
            .contains("\"deviceId\":\"device-1\"")
            .contains("\"question\":\"What is SwiftUI?\"")
            .contains("\"expectedAnswerHint\":\"UI framework\"")
            .contains("\"topic\":\"SwiftUI\"")
            .contains("\"difficultyLevel\":5")
            .contains("\"language\":\"en\"")
            .contains("\"sound\":\"default\"")
            .contains("\"intervalMinutes\":15")
    }

    @Test
    fun `publish methods return false when publisher throws`(): Unit = runBlocking {
        val service = service(enabled = true, publisher = RecordingPublisher(fail = true))

        assertThat(service.publishPush(pushEvent())).isFalse()
    }

    private fun service(enabled: Boolean, publisher: RedisStreamPublishOperations): RedisStreamPushPublisher {
        val properties = BuddyStudyProperties().apply {
            streams.enabled = enabled
            streams.key = "buddystudy-events-v1"
        }
        return RedisStreamPushPublisher(properties, publisher)
    }

    private fun pushEvent(
        topic: String = "SwiftUI",
        deviceId: String = "device-1",
        userId: Long? = 11,
    ) = QuestionPushRequest(
        recordId = 10,
        studyId = 77,
        deviceId = deviceId,
        userId = userId,
        question = "What is SwiftUI?",
        expectedAnswerHint = "UI framework",
        topic = topic,
        difficultyLevel = 5,
        language = "en",
        sound = "default",
        intervalMinutes = 15,
        createdAt = Instant.parse("2026-06-08T00:00:00Z"),
    )

    private data class PublishRequest(val streamKey: String, val fields: Map<String, String>)

    private class RecordingPublisher(private val fail: Boolean = false) : RedisStreamPublishOperations {
        val requests = mutableListOf<PublishRequest>()

        override suspend fun publish(streamKey: String, fields: Map<String, String>): RedisStreamPublishedMessage {
            if (fail) throw IllegalStateException("publish failed")
            requests += PublishRequest(streamKey, fields)
            return RedisStreamPublishedMessage(streamKey, "record-1")
        }
    }
}
