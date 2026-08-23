package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishedMessage
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

interface RedisStreamObjectPublisher {
    suspend fun publish(
        topic: RedisStreamTopic,
        eventType: String,
        eventId: String,
        payload: Any,
        fields: Map<String, String> = emptyMap(),
    ): RedisStreamPublishedMessage
}

@Component
class JacksonRedisStreamCodec(
    private val objectMapper: ObjectMapper,
) {
    fun write(payload: Any): String = objectMapper.writeValueAsString(payload)

    fun read(rawPayload: String, payloadType: Class<*>): Any =
        objectMapper.readValue(rawPayload, payloadType)
}

@Component
class JacksonRedisStreamPublisher(
    private val streams: RedisStreamPublishOperations,
    private val codec: JacksonRedisStreamCodec,
) : RedisStreamObjectPublisher {
    override suspend fun publish(
        topic: RedisStreamTopic,
        eventType: String,
        eventId: String,
        payload: Any,
        fields: Map<String, String>,
    ): RedisStreamPublishedMessage {
        val envelope = fields + mapOf(
            EVENT_ID_FIELD to eventId,
            EVENT_TYPE_FIELD to eventType,
            PAYLOAD_FIELD to codec.write(payload),
        )
        return streams.publish(topic, envelope)
    }

    companion object {
        const val EVENT_ID_FIELD = "eventId"
        const val EVENT_TYPE_FIELD = "eventType"
        const val PAYLOAD_FIELD = "payload"
    }
}
