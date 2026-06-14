package com.buddystuddy.backend

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.adapter.outbound.stream.RedisStreamPushPublisher
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
import com.redisstream.consumer.ProducerRoutingShard
import com.redisstream.producer.ProducerRoute
import com.redisstream.producer.PublishedRedisStreamMessage
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Instant
import java.util.stream.Stream

@ExtendWith(OutputCaptureExtension::class)
class RedisStreamPushPublisherTest {
    @Test
    fun `publish methods return false when streams are disabled`() {
        val service = service(enabled = false, pushPublisher = RecordingPublisher())

        assertThat(service.publishPush(pushEvent())).isFalse()
    }

    @Test
    fun `enabled streams require push publisher bean`() {
        assertThatThrownBy { service(enabled = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("pushStreamPublisher bean is required")
    }

    @Test
    fun `push event publishes with topic as stream key hint`(output: CapturedOutput) {
        val publisher = RecordingPublisher()
        val service = service(enabled = true, pushPublisher = publisher)

        assertThat(service.publishPush(pushEvent(topic = "SwiftUI"))).isTrue()

        val request = publisher.requests.single()
        assertThat(request.key).isEqualTo("SwiftUI")
        assertThat(request.fields).containsEntry("eventType", "QUESTION_PUSH_REQUESTED")
        assertThat(request.fields).containsEntry("recordId", "10")
        assertThat(request.options.maxLen).isEqualTo(100_000)
        assertThat(request.options.approximateTrimming).isTrue()
        assertThat(output.out)
            .contains("redis_stream_publish_started")
            .contains("redis_stream_publish_succeeded")
            .contains("eventType=QUESTION_PUSH_REQUESTED")
            .contains("recordId=10")
    }

    @Test
    fun `push event falls back to record id when topic is blank`() {
        val publisher = RecordingPublisher()
        val service = service(enabled = true, pushPublisher = publisher)

        assertThat(service.publishPush(pushEvent(topic = ""))).isTrue()

        assertThat(publisher.requests.single().key).isEqualTo("10")
    }

    @Test
    fun `publish methods return false when publisher throws`() {
        val failing = RecordingPublisher(fail = true)
        val service = service(enabled = true, pushPublisher = failing)

        assertThat(service.publishPush(pushEvent())).isFalse()
    }

    private fun service(
        enabled: Boolean,
        pushPublisher: RedisStreamPublisher? = null,
    ): RedisStreamPushPublisher {
        val properties = BuddyStuddyProperties().apply {
            streams.enabled = enabled
        }
        return RedisStreamPushPublisher(
            properties,
            provider(pushPublisher),
        )
    }

    private fun pushEvent(topic: String = "SwiftUI") = QuestionPushRequest(
        recordId = 10,
        deviceId = "device-1",
        userId = 11,
        question = "What is SwiftUI?",
        expectedAnswerHint = "UI framework",
        topic = topic,
        difficultyLevel = 5,
        language = "en",
        sound = "default",
        intervalMinutes = 15,
        createdAt = Instant.parse("2026-06-08T00:00:00Z"),
    )

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

    private class RecordingPublisher(private val fail: Boolean = false) : RedisStreamPublisher {
        val requests = mutableListOf<PublishRequest>()

        override fun publish(
            partitionKey: String?,
            fields: Map<String, String>,
            options: RedisStreamPublishOptions,
        ): PublishedRedisStreamMessage {
            if (fail) throw IllegalStateException("publish failed")
            requests += PublishRequest(partitionKey, fields, options)
            val streamKey = "stream-$partitionKey"
            return PublishedRedisStreamMessage(
                streamKey,
                "record-1",
                ProducerRoute(streamKey, ProducerRoutingShard(0, streamKey, 0), 1),
            )
        }
    }
}
