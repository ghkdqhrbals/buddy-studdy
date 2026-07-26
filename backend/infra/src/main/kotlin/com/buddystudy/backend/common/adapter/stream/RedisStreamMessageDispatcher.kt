package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamConsumerOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class RedisStreamMessageDispatcher(
    private val streams: RedisStreamConsumerOperations,
    private val codec: JacksonRedisStreamCodec,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    internal suspend fun dispatch(
        bean: Any,
        method: RedisStreamHandlerMethod,
        eventType: String,
        payloadType: Class<*>,
        group: String,
        message: RedisStreamMessage,
        claimed: Boolean,
    ) {
        val actualEventType = message.fields[JacksonRedisStreamPublisher.EVENT_TYPE_FIELD]
        if (actualEventType != eventType) {
            streams.acknowledge(message, group)
            return
        }
        try {
            val rawPayload = message.fields[JacksonRedisStreamPublisher.PAYLOAD_FIELD]
                ?: throw IllegalArgumentException("Redis Stream payload field is required.")
            val payload = codec.read(rawPayload, payloadType)
            val context = StreamMessageContext(
                streamKey = message.streamKey,
                recordId = message.recordId,
                eventId = message.fields[JacksonRedisStreamPublisher.EVENT_ID_FIELD],
                eventType = actualEventType,
                fields = message.fields,
                claimed = claimed,
            )
            method.invoke(bean, payload, context)
            streams.acknowledge(message, group)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_handler_failed method={} stream={} redisRecordId={} eventId={} eventType={} claimed={} error={}",
                method.name,
                message.streamKey,
                message.recordId,
                message.fields[JacksonRedisStreamPublisher.EVENT_ID_FIELD],
                actualEventType,
                claimed,
                error.cause?.message ?: error.message,
            )
        }
    }
}
