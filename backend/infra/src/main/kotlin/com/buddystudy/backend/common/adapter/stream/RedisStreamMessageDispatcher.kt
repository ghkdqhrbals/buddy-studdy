package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.inbound.web.ApiLoggingPolicy
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamConsumerOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class RedisStreamMessageDispatcher(
    private val streams: RedisStreamConsumerOperations,
    private val codec: JacksonRedisStreamCodec,
    private val loggingPolicy: ApiLoggingPolicy,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    internal suspend fun dispatch(
        bean: Any,
        method: RedisStreamHandlerMethod,
        eventType: String,
        payloadType: Class<*>,
        group: String,
        options: StreamOptions,
        message: RedisStreamMessage,
        claimed: Boolean,
    ) {
        val actualEventType = message.fields[JacksonRedisStreamPublisher.EVENT_TYPE_FIELD]
        if (actualEventType != eventType) {
            logger.warn(
                "redis_stream_event_type_mismatch method={} stream={} redisRecordId={} eventId={} expectedEventType={} actualEventType={} group={} options={} claimed={}",
                method.name,
                message.streamKey,
                message.recordId,
                message.fields[JacksonRedisStreamPublisher.EVENT_ID_FIELD],
                eventType,
                actualEventType,
                group,
                options,
                claimed,
            )
            complete(message, group, options)
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
            logger.debug(
                "redis_stream_handler_started method={} stream={} redisRecordId={} eventId={} eventType={} group={} claimed={}",
                method.name,
                message.streamKey,
                message.recordId,
                context.eventId,
                actualEventType,
                group,
                claimed,
            )
            method.invoke(bean, payload, context)
            complete(message, group, options)
            logger.debug(
                "redis_stream_handler_completed method={} stream={} redisRecordId={} eventId={} eventType={} group={} options={} claimed={}",
                method.name,
                message.streamKey,
                message.recordId,
                context.eventId,
                actualEventType,
                group,
                options,
                claimed,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val rootError = error.unwrapReflectionFailure()
            val arguments: Array<Any?> = arrayOf(
                method.name,
                message.streamKey,
                message.recordId,
                message.fields[JacksonRedisStreamPublisher.EVENT_ID_FIELD],
                actualEventType,
                group,
                options,
                claimed,
                rootError.javaClass.name,
                rootError.message,
            )
            if (loggingPolicy.includesStackTrace) {
                logger.warn(HANDLER_FAILED_LOG, *arguments, rootError)
            } else {
                logger.warn(HANDLER_FAILED_LOG, *arguments)
            }
        }
    }

    private suspend fun complete(message: RedisStreamMessage, group: String, options: StreamOptions) {
        when (options) {
            StreamOptions.NONE -> Unit
            StreamOptions.ACK -> streams.acknowledge(message, group)
            StreamOptions.ACK_DEL -> streams.acknowledgeAndDelete(message, group)
        }
    }

    private fun Throwable.unwrapReflectionFailure(): Throwable {
        var current = this
        while (current is java.lang.reflect.InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }
        return current
    }

    private companion object {
        const val HANDLER_FAILED_LOG =
            "redis_stream_handler_failed method={} stream={} redisRecordId={} eventId={} eventType={} group={} " +
                "options={} claimed={} errorType={} error={}"
    }
}
