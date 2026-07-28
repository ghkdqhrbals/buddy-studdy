package com.buddystudy.backend.common.application.outbox

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.study.application.port.outbound.ClaimedQuestionPushOutbox
import com.buddystudy.backend.study.application.port.outbound.QuestionPushOutboxPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class OutboxPublicationServiceTest {
    @Test
    fun `immediate publication claims publishes and completes both outbox types`(): Unit = runBlocking {
        val domainOutbox = FakeDomainOutbox(domainEvent(1))
        val pushOutbox = FakePushOutbox(pushEvent(2))
        val domainPublisher = FakeDomainPublisher()
        val pushPublisher = FakePushPublisher()
        val service = service(domainOutbox, pushOutbox, domainPublisher, pushPublisher)

        val result = service.publishNow(
            listOf(
                OutboxReference(OutboxType.DOMAIN_EVENT, 1),
                OutboxReference(OutboxType.QUESTION_PUSH, 2),
            ),
        )

        assertThat(result).isEqualTo(OutboxPublishSummary(attempted = 2, published = 2, retryScheduled = 0))
        assertThat(domainOutbox.published).containsExactly(1L)
        assertThat(pushOutbox.published).containsExactly(2L)
        assertThat(domainPublisher.calls).isEqualTo(1)
        assertThat(pushPublisher.calls).isEqualTo(1)
    }

    @Test
    fun `publish failure leaves event retryable with incremented attempt`(): Unit = runBlocking {
        val domainOutbox = FakeDomainOutbox(domainEvent(1))
        val domainPublisher = FakeDomainPublisher(failure = IllegalStateException("redis unavailable"))
        val service = service(domainOutbox, FakePushOutbox(), domainPublisher, FakePushPublisher())

        val result = service.publishNow(listOf(OutboxReference(OutboxType.DOMAIN_EVENT, 1)))

        assertThat(result).isEqualTo(OutboxPublishSummary(attempted = 1, published = 0, retryScheduled = 1))
        assertThat(domainOutbox.retried).containsExactly(1L)
        assertThat(domainOutbox.retryAttempts).containsExactly(1)
    }

    @Test
    fun `concurrent immediate publishers publish a claimed row only once`(): Unit = runBlocking {
        val domainOutbox = FakeDomainOutbox(domainEvent(1))
        val domainPublisher = FakeDomainPublisher()
        val service = service(domainOutbox, FakePushOutbox(), domainPublisher, FakePushPublisher())
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
            FakePushOutbox(),
            FakeDomainPublisher(),
            FakePushPublisher(),
        )

        val error = runCatching { service.recoverPending() }.exceptionOrNull()

        assertThat(error)
            .isInstanceOf(OutboxRecoveryClaimException::class.java)
            .hasCause(failure)
    }

    private fun service(
        domainOutbox: FakeDomainOutbox,
        pushOutbox: FakePushOutbox,
        domainPublisher: FakeDomainPublisher,
        pushPublisher: FakePushPublisher,
    ) = OutboxPublicationService(
        properties = BuddyStudyProperties().apply { streams.enabled = true },
        domainOutbox = domainOutbox,
        pushOutbox = pushOutbox,
        domainPublisher = domainPublisher,
        pushPublisher = pushPublisher,
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

        override suspend fun markPublished(id: Long, claimToken: String, publishedAt: Instant): Boolean {
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

    private class FakePushOutbox(vararg initial: ClaimedQuestionPushOutbox) : QuestionPushOutboxPort {
        private val available = initial.associateBy { it.id }.toMutableMap()
        val published = mutableListOf<Long>()

        override suspend fun enqueue(request: QuestionPushRequest, now: Instant): Long = 1

        override suspend fun claim(id: Long, now: Instant, staleBefore: Instant): ClaimedQuestionPushOutbox? =
            synchronized(this) { available.remove(id) }

        override suspend fun claimBatch(
            now: Instant,
            staleBefore: Instant,
            limit: Int,
        ): List<ClaimedQuestionPushOutbox> = synchronized(this) {
            available.values.take(limit).also { rows -> rows.forEach { available.remove(it.id) } }
        }

        override suspend fun markPublished(id: Long, claimToken: String, publishedAt: Instant): Boolean {
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
        ): Boolean = true
    }

    private class FakeDomainPublisher(private val failure: Throwable? = null) : DomainEventPublishPort {
        var calls = 0
        override suspend fun publish(event: ClaimedRedisOutboxEvent): String {
            calls += 1
            failure?.let { throw it }
            return "1-0"
        }
    }

    private class FakePushPublisher : QuestionPushPublishPort {
        var calls = 0
        override suspend fun publishPush(request: QuestionPushRequest): Boolean {
            calls += 1
            return true
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

        fun pushEvent(id: Long) = ClaimedQuestionPushOutbox(
            id = id,
            request = QuestionPushRequest(
                recordId = 10,
                studyId = 20,
                deviceId = "device",
                userId = 30,
                question = "question",
                expectedAnswerHint = null,
                topic = "topic",
                difficultyLevel = 1,
                language = "ko",
                sound = null,
                intervalMinutes = 15,
            ),
            attempts = 0,
            createdAt = Instant.parse("2026-07-27T00:00:00Z"),
            claimToken = "claim-$id",
        )
    }
}
