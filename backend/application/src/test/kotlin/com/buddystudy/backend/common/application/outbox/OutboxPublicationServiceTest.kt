package com.buddystudy.backend.common.application.outbox

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class OutboxPublicationServiceTest {
    @Test
    fun `immediate publication claims publishes and completes notification outbox`(): Unit = runBlocking {
        val domainOutbox = FakeDomainOutbox(domainEvent(1))
        val domainPublisher = FakeDomainPublisher()
        val service = service(domainOutbox, domainPublisher)

        val result = service.publishNow(listOf(OutboxReference(OutboxType.DOMAIN_EVENT, 1)))

        assertThat(result).isEqualTo(OutboxPublishSummary(attempted = 1, published = 1, retryScheduled = 0))
        assertThat(domainOutbox.published).containsExactly(1L)
        assertThat(domainPublisher.calls).isEqualTo(1)
    }

    @Test
    fun `publish failure leaves event retryable with incremented attempt`(): Unit = runBlocking {
        val domainOutbox = FakeDomainOutbox(domainEvent(1))
        val domainPublisher = FakeDomainPublisher(failure = IllegalStateException("redis unavailable"))
        val service = service(domainOutbox, domainPublisher)

        val result = service.publishNow(listOf(OutboxReference(OutboxType.DOMAIN_EVENT, 1)))

        assertThat(result).isEqualTo(OutboxPublishSummary(attempted = 1, published = 0, retryScheduled = 1))
        assertThat(domainOutbox.retried).containsExactly(1L)
        assertThat(domainOutbox.retryAttempts).containsExactly(1)
    }

    @Test
    fun `concurrent immediate publishers publish a claimed row only once`(): Unit = runBlocking {
        val domainOutbox = FakeDomainOutbox(domainEvent(1))
        val domainPublisher = FakeDomainPublisher()
        val service = service(domainOutbox, domainPublisher)
        val reference = OutboxReference(OutboxType.DOMAIN_EVENT, 1)

        List(20) {
            async(Dispatchers.Default) { service.publishNow(listOf(reference)) }
        }.awaitAll()

        assertThat(domainPublisher.calls).isEqualTo(1)
        assertThat(domainOutbox.published).containsExactly(1L)
    }

    @Test
    fun `recovery claim failure is surfaced instead of reported as an empty success`(): Unit = runBlocking {
        val failure = IllegalStateException("Could not construct new record")
        val service = service(
            FakeDomainOutbox(claimBatchFailure = failure),
            FakeDomainPublisher(),
        )

        val error = runCatching { service.recoverPending() }.exceptionOrNull()

        assertThat(error)
            .isInstanceOf(OutboxRecoveryClaimException::class.java)
            .hasCause(failure)
    }

    private fun service(
        domainOutbox: FakeDomainOutbox,
        domainPublisher: FakeDomainPublisher,
    ) = OutboxPublicationService(
        properties = BuddyStudyProperties().apply { streams.enabled = true },
        domainOutbox = domainOutbox,
        domainPublisher = domainPublisher,
    )

    private class FakeDomainOutbox(
        vararg initial: ClaimedRedisOutboxEvent,
        private val claimBatchFailure: Throwable? = null,
    ) : RedisEventOutboxPort {
        private val available = initial.associateBy { it.id }.toMutableMap()
        val published = mutableListOf<Long>()
        val retried = mutableListOf<Long>()
        val retryAttempts = mutableListOf<Int>()

        override suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant): Long = 1

        override suspend fun claim(id: Long, now: Instant, staleBefore: Instant): ClaimedRedisOutboxEvent? =
            synchronized(this) { available.remove(id) }

        override suspend fun claimBatch(
            now: Instant,
            staleBefore: Instant,
            limit: Int,
        ): List<ClaimedRedisOutboxEvent> = synchronized(this) {
            claimBatchFailure?.let { throw it }
            available.values.take(limit).also { rows -> rows.forEach { available.remove(it.id) } }
        }

        override suspend fun markPublished(
            id: Long,
            claimToken: String,
            publication: PublishedStreamRecord,
            publishedAt: Instant,
        ): Boolean {
            published += id
            return true
        }

        override suspend fun markRetry(
            id: Long,
            claimToken: String,
            attempts: Int,
            nextAttemptAt: Instant,
            error: String,
            updatedAt: Instant,
        ): Boolean {
            retried += id
            retryAttempts += attempts
            return true
        }
    }

    private class FakeDomainPublisher(private val failure: Throwable? = null) : DomainEventPublishPort {
        var calls = 0
        override suspend fun publish(event: ClaimedRedisOutboxEvent): PublishedStreamRecord {
            calls += 1
            failure?.let { throw it }
            return PublishedStreamRecord("notification.message.requested.v1", "1-0")
        }
    }

    private companion object {
        fun domainEvent(id: Long) = ClaimedRedisOutboxEvent(
            id = id,
            eventId = "event-$id",
            eventType = RedisOutboxEventType.NOTIFICATION_REQUESTED,
            payloadVersion = 1,
            payloadJson = "{}",
            attempts = 0,
            createdAt = Instant.parse("2026-07-27T00:00:00Z"),
            claimToken = "claim-$id",
        )
    }
}
