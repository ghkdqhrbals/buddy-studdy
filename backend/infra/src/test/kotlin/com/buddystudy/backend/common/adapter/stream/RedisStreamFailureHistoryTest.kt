package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisStreamFailureHistoryTest {
    @Test
    fun `failure is scheduled for recovery before the third attempt`(): Unit = runBlocking {
        val inbox = RecordingInbox(attempt = 2)
        val history = RedisStreamInboxFailureHistory(inbox)

        val disposition = history.recordRetryable(message(), "sample-group", IllegalStateException("retry"))

        assertThat(disposition).isEqualTo(RedisStreamFailureDisposition.RETRY)
        assertThat(inbox.retryAttempts).containsExactly(2)
        assertThat(inbox.failedAttempts).isEmpty()
    }

    @Test
    fun `third failure is terminal so the dispatcher can discard only the pending delivery`(): Unit = runBlocking {
        val inbox = RecordingInbox(attempt = MAX_STREAM_HANDLER_ATTEMPTS)
        val history = RedisStreamInboxFailureHistory(inbox)

        val disposition = history.recordRetryable(message(), "sample-group", IllegalStateException("exhausted"))

        assertThat(disposition).isEqualTo(RedisStreamFailureDisposition.DISCARD)
        assertThat(inbox.retryAttempts).isEmpty()
        assertThat(inbox.failedAttempts).containsExactly(MAX_STREAM_HANDLER_ATTEMPTS)
    }

    private fun message() = RedisStreamMessage(
        streamKey = "sample.event.failed.v1",
        recordId = "1-0",
        fields = mapOf("eventId" to "event-1"),
    )

    private class RecordingInbox(
        private val attempt: Int,
    ) : StreamInboxPort {
        val retryAttempts = mutableListOf<Int>()
        val failedAttempts = mutableListOf<Int>()

        override suspend fun claim(
            eventId: String,
            consumerGroup: String,
            correlationId: String,
            leaseDuration: Duration,
            now: Instant,
            streamKey: String,
        ) = StreamInboxClaim(
            eventId = eventId,
            consumerGroup = consumerGroup,
            claimToken = "claim-$attempt",
            attempt = attempt,
            streamKey = streamKey,
        )

        override suspend fun markSucceeded(claim: StreamInboxClaim, now: Instant) = true

        override suspend fun releaseForRetry(
            claim: StreamInboxClaim,
            errorType: String,
            errorMessage: String,
            now: Instant,
        ): Boolean {
            retryAttempts += claim.attempt
            return true
        }

        override suspend fun markFailed(
            claim: StreamInboxClaim,
            errorType: String,
            errorMessage: String,
            now: Instant,
        ): Boolean {
            failedAttempts += claim.attempt
            return true
        }
    }
}
