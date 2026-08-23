package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class RedisStreamInboxFailureHistoryTest {
    @Test
    fun `terminal decoding failure creates a failed Inbox attempt with a synthetic event id`() = runBlocking<Unit> {
        val inbox = RecordingInbox()
        val history = RedisStreamInboxFailureHistory(inbox)
        val message = RedisStreamMessage(
            streamKey = "study.answer-grading.requested.v1",
            recordId = "1785370000000-0",
            fields = emptyMap(),
        )

        val recorded = history.recordTerminal(
            message,
            "bs-backend-answer-grading",
            IllegalArgumentException("payload is invalid"),
        )

        assertThat(recorded).isTrue()
        assertThat(inbox.claimedEventId)
            .isEqualTo("redis-record:study.answer-grading.requested.v1:1785370000000-0")
        assertThat(inbox.claimedStreamKey).isEqualTo(message.streamKey)
        assertThat(inbox.failedErrorType).isEqualTo(IllegalArgumentException::class.java.name)
        assertThat(inbox.failedErrorMessage).isEqualTo("payload is invalid")
    }

    @Test
    fun `retryable handler failure releases the Inbox attempt for Redis recovery`() = runBlocking<Unit> {
        val inbox = RecordingInbox()
        val history = RedisStreamInboxFailureHistory(inbox)
        val message = RedisStreamMessage(
            streamKey = "study.answer-grading.requested.v1",
            recordId = "1785370000001-0",
            fields = mapOf("eventId" to "grading-event-1"),
        )

        val recorded = history.recordRetryable(
            message,
            "bs-backend-answer-grading",
            IllegalStateException("database unavailable"),
        )

        assertThat(recorded).isEqualTo(RedisStreamFailureDisposition.RETRY)
        assertThat(inbox.claimedEventId).isEqualTo("grading-event-1")
        assertThat(inbox.retryErrorType).isEqualTo(IllegalStateException::class.java.name)
        assertThat(inbox.retryErrorMessage).isEqualTo("database unavailable")
    }

    private class RecordingInbox : StreamInboxPort {
        var claimedEventId: String? = null
        var claimedStreamKey: String? = null
        var failedErrorType: String? = null
        var failedErrorMessage: String? = null
        var retryErrorType: String? = null
        var retryErrorMessage: String? = null

        override suspend fun claim(
            eventId: String,
            consumerGroup: String,
            correlationId: String,
            leaseDuration: Duration,
            now: Instant,
            streamKey: String,
        ): StreamInboxClaim {
            claimedEventId = eventId
            claimedStreamKey = streamKey
            return StreamInboxClaim(eventId, consumerGroup, "claim-1", 1, streamKey)
        }

        override suspend fun markSucceeded(claim: StreamInboxClaim, now: Instant): Boolean =
            error("Not expected.")

        override suspend fun releaseForRetry(
            claim: StreamInboxClaim,
            errorType: String,
            errorMessage: String,
            now: Instant,
        ): Boolean {
            retryErrorType = errorType
            retryErrorMessage = errorMessage
            return true
        }

        override suspend fun markFailed(
            claim: StreamInboxClaim,
            errorType: String,
            errorMessage: String,
            now: Instant,
        ): Boolean {
            failedErrorType = errorType
            failedErrorMessage = errorMessage
            return true
        }
    }
}
