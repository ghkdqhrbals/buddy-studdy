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
    private val failureHistory: RedisStreamFailureHistory,
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
            val error = RedisStreamEventTypeMismatchException(eventType, actualEventType)
            logFailure(method, message, actualEventType, group, options, claimed, error)
            if (failureHistory.recordTerminal(message, group, error)) {
                complete(message, group, options)
            }
            return
        }
        val payload = try {
            val rawPayload = message.fields[JacksonRedisStreamPublisher.PAYLOAD_FIELD]
                ?: throw IllegalArgumentException("Redis Stream payload field is required.")
            codec.read(rawPayload, payloadType)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error.isFatalStreamWorkerFailure()) throw error
            val rootError = error.unwrapReflectionFailure()
            logFailure(method, message, actualEventType, group, options, claimed, rootError)
            if (failureHistory.recordTerminal(message, group, rootError)) {
                complete(message, group, options)
            }
            return
        }
        val context = StreamMessageContext(
            streamKey = message.streamKey,
            recordId = message.recordId,
            eventId = message.fields[JacksonRedisStreamPublisher.EVENT_ID_FIELD],
            eventType = actualEventType,
            fields = message.fields,
            claimed = claimed,
        )
        try {
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
        } catch (error: Throwable) {
            if (error.isFatalStreamWorkerFailure()) throw error
            val rootError = error.unwrapReflectionFailure()
            logFailure(method, message, actualEventType, group, options, claimed, rootError)
            failureHistory.recordRetryable(message, group, rootError)
        }
    }

    private fun logFailure(
        method: RedisStreamHandlerMethod,
        message: RedisStreamMessage,
        actualEventType: String?,
        group: String,
        options: StreamOptions,
        claimed: Boolean,
        rootError: Throwable,
    ) {
        logger.error(
            HANDLER_FAILED_LOG,
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
            rootError,
        )
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

private class RedisStreamEventTypeMismatchException(
    expected: String,
    actual: String?,
) : IllegalArgumentException("Expected eventType '$expected' but received '${actual ?: "null"}'.")
