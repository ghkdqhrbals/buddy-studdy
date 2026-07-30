package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

interface RedisStreamFailureHistory {
    suspend fun recordTerminal(
        message: RedisStreamMessage,
        consumerGroup: String,
        error: Throwable,
    ): Boolean

    suspend fun recordRetryable(
        message: RedisStreamMessage,
        consumerGroup: String,
        error: Throwable,
    ): Boolean
}

@Component
class RedisStreamInboxFailureHistory(
    private val inbox: StreamInboxPort,
) : RedisStreamFailureHistory {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun recordTerminal(
        message: RedisStreamMessage,
        consumerGroup: String,
        error: Throwable,
    ): Boolean = record(message, consumerGroup, error, terminal = true)

    override suspend fun recordRetryable(
        message: RedisStreamMessage,
        consumerGroup: String,
        error: Throwable,
    ): Boolean = record(message, consumerGroup, error, terminal = false)

    private suspend fun record(
        message: RedisStreamMessage,
        consumerGroup: String,
        error: Throwable,
        terminal: Boolean,
    ): Boolean {
        val now = Instant.now()
        val eventId = message.eventIdOrSynthetic()
        return try {
            val claim = inbox.claim(
                eventId = eventId,
                consumerGroup = consumerGroup,
                correlationId = message.recordId.take(MAX_CORRELATION_ID_LENGTH),
                leaseDuration = FAILURE_CLAIM_LEASE,
                now = now,
                streamKey = message.streamKey,
            ) ?: return true
            if (terminal) {
                inbox.markFailed(
                    claim = claim,
                    errorType = error.javaClass.name,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                    now = now,
                )
            } else {
                inbox.releaseForRetry(
                    claim = claim,
                    errorType = error.javaClass.name,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                    now = now,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (historyError: Throwable) {
            logger.error(
                "redis_stream_failure_history_record_failed stream={} redisRecordId={} eventId={} group={} " +
                    "terminal={} originalErrorType={} historyErrorType={} historyError={}",
                message.streamKey,
                message.recordId,
                eventId,
                consumerGroup,
                terminal,
                error.javaClass.name,
                historyError.javaClass.name,
                historyError.message,
                historyError,
            )
            false
        }
    }

    private fun RedisStreamMessage.eventIdOrSynthetic(): String =
        fields[JacksonRedisStreamPublisher.EVENT_ID_FIELD]
            ?.takeIf(String::isNotBlank)
            ?.take(MAX_EVENT_ID_LENGTH)
            ?: "redis-record:$streamKey:$recordId".take(MAX_EVENT_ID_LENGTH)

    private companion object {
        val FAILURE_CLAIM_LEASE: Duration = Duration.ofMinutes(5)
        const val MAX_EVENT_ID_LENGTH = 120
        const val MAX_CORRELATION_ID_LENGTH = 36
    }
}
