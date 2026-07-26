package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishedMessage
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JacksonRedisStreamCodecTest {
    private val codec = JacksonRedisStreamCodec(JsonMapperProvider.mapper)

    @Test
    fun `Jackson converts a stream payload to its declared object type`() {
        val raw = """
            {"id":17,"name":"typed-event","createdAt":"2026-07-27T00:00:00Z"}
        """.trimIndent()

        val payload = codec.read(raw, SamplePayload::class.java) as SamplePayload

        assertThat(payload).isEqualTo(
            SamplePayload(
                id = 17,
                name = "typed-event",
                createdAt = Instant.parse("2026-07-27T00:00:00Z"),
            ),
        )
    }

    @Test
    fun `object publisher writes a typed payload into the standard stream envelope`(): Unit = runBlocking {
        val redis = RecordingRedisPublisher()
        val publisher = JacksonRedisStreamPublisher(redis, codec)
        val payload = SamplePayload(17, "typed-event", Instant.parse("2026-07-27T00:00:00Z"))

        publisher.publish(
            topic = RedisStreamTopic.DOMAIN_EVENTS,
            eventType = "SAMPLE_EVENT",
            eventId = "event-17",
            payload = payload,
        )

        assertThat(redis.fields).containsEntry("eventId", "event-17")
        assertThat(redis.fields).containsEntry("eventType", "SAMPLE_EVENT")
        assertThat(redis.fields["payload"])
            .contains("\"id\":17")
            .contains("\"name\":\"typed-event\"")
            .contains("\"createdAt\":\"2026-07-27T00:00:00Z\"")
    }

    private data class SamplePayload(
        val id: Long,
        val name: String,
        val createdAt: Instant,
    )

    private class RecordingRedisPublisher : RedisStreamPublishOperations {
        var fields: Map<String, String> = emptyMap()

        override suspend fun publish(
            topic: RedisStreamTopic,
            fields: Map<String, String>,
        ): RedisStreamPublishedMessage {
            this.fields = fields
            return RedisStreamPublishedMessage(topic.apiName, "1-0")
        }
    }
}
