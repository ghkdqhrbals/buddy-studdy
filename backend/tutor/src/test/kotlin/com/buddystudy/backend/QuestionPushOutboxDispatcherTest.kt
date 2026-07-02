package com.buddystudy.backend

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.adapter.inbound.scheduler.QuestionPushOutboxDispatchJob
import com.buddystudy.backend.study.adapter.inbound.scheduler.QuestionPushOutboxDispatcher
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionPushOutboxJpaRepository
import com.buddystudy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystudy.study.domain.entity.QuestionPushOutboxEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystudy-push-outbox;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
        "spring.autoconfigure.exclude=com.redisstream.RedisStreamCoordinatorAutoConfiguration,com.redisstream.producer.ProducerRoutingAutoConfiguration,com.redisstream.consumer.CoordinatorConsumerAutoConfiguration",
    ]
)
class QuestionPushOutboxDispatcherTest {
    @Autowired lateinit var outbox: QuestionPushOutboxJpaRepository

    @BeforeEach
    fun clearOutbox() {
        outbox.deleteAll()
    }

    @Test
    fun `dispatcher publishes pending push outbox and marks published`() {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        val item = outbox.save(
            QuestionPushOutboxEntity(
                recordId = 10,
                deviceId = "device-1",
                userId = 20,
                question = "Question?",
                expectedAnswerHint = "Hint",
                topic = "Kotlin",
                difficultyLevel = 7,
                language = "ko",
                sound = "default",
                intervalMinutes = 15,
                status = "PENDING",
                nextAttemptAt = now.minusSeconds(1),
                createdAt = now.minusSeconds(10),
                updatedAt = now.minusSeconds(10),
            )
        )
        val publisher = CapturingPushPublisher(true)
        val dispatcher = QuestionPushOutboxDispatcher(
            BuddyStudyProperties(
                scheduler = BuddyStudyProperties.Scheduler(enabled = true),
                streams = BuddyStudyProperties.Streams(enabled = true),
            ),
            outbox,
            publisher,
        )

        dispatcher.dispatchPendingPushes()

        val published = outbox.findById(item.id).orElseThrow()
        assertThat(publisher.requests).hasSize(1)
        assertThat(publisher.requests[0].recordId).isEqualTo(10)
        assertThat(published.status).isEqualTo("PUBLISHED")
        assertThat(published.publishedAt).isNotNull()
        assertThat(published.lastError).isNull()
    }

    @Test
    fun `dispatcher keeps failed publish pending for retry`() {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        val item = outbox.save(
            QuestionPushOutboxEntity(
                recordId = 11,
                deviceId = "device-2",
                userId = 21,
                question = "Retry?",
                topic = "Redis",
                nextAttemptAt = now.minusSeconds(1),
                createdAt = now.minusSeconds(10),
                updatedAt = now.minusSeconds(10),
            )
        )
        val dispatcher = QuestionPushOutboxDispatcher(
            BuddyStudyProperties(
                scheduler = BuddyStudyProperties.Scheduler(enabled = true),
                streams = BuddyStudyProperties.Streams(enabled = true),
            ),
            outbox,
            CapturingPushPublisher(false),
        )

        dispatcher.dispatchPendingPushes()

        val retry = outbox.findById(item.id).orElseThrow()
        assertThat(retry.status).isEqualTo("PENDING")
        assertThat(retry.attempts).isEqualTo(1)
        assertThat(retry.nextAttemptAt).isAfter(now)
        assertThat(retry.lastError).isEqualTo("Push stream publish failed.")
    }

    @Test
    fun `dispatcher isolates publish exception and continues remaining items`() {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        val failing = outbox.save(
            QuestionPushOutboxEntity(
                recordId = 12,
                deviceId = "device-fail",
                userId = 22,
                question = "Fails?",
                topic = "Redis",
                nextAttemptAt = now.minusSeconds(1),
                createdAt = now.minusSeconds(20),
                updatedAt = now.minusSeconds(20),
            )
        )
        val succeeding = outbox.save(
            QuestionPushOutboxEntity(
                recordId = 13,
                deviceId = "device-ok",
                userId = 23,
                question = "Succeeds?",
                topic = "Kotlin",
                nextAttemptAt = now.minusSeconds(1),
                createdAt = now.minusSeconds(10),
                updatedAt = now.minusSeconds(10),
            )
        )
        val dispatcher = QuestionPushOutboxDispatcher(
            BuddyStudyProperties(
                scheduler = BuddyStudyProperties.Scheduler(enabled = true),
                streams = BuddyStudyProperties.Streams(enabled = true),
            ),
            outbox,
            ThrowingThenCapturingPushPublisher(failingRecordId = 12),
        )

        assertThatCode { dispatcher.dispatchPendingPushes() }.doesNotThrowAnyException()

        val retry = outbox.findById(failing.id).orElseThrow()
        val published = outbox.findById(succeeding.id).orElseThrow()
        assertThat(retry.status).isEqualTo("PENDING")
        assertThat(retry.attempts).isEqualTo(1)
        assertThat(retry.lastError).contains("boom")
        assertThat(published.status).isEqualTo("PUBLISHED")
        assertThat(published.publishedAt).isNotNull()
    }

    @Test
    fun `managed job dispatches pending push outbox and returns processed summary`() {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        outbox.save(
            QuestionPushOutboxEntity(
                recordId = 14,
                deviceId = "device-job",
                userId = 24,
                question = "Managed?",
                topic = "Ops",
                nextAttemptAt = now.minusSeconds(1),
                createdAt = now.minusSeconds(10),
                updatedAt = now.minusSeconds(10),
            )
        )
        val dispatcher = QuestionPushOutboxDispatcher(
            BuddyStudyProperties(
                scheduler = BuddyStudyProperties.Scheduler(enabled = true),
                streams = BuddyStudyProperties.Streams(enabled = true),
            ),
            outbox,
            CapturingPushPublisher(true),
        )
        val job = QuestionPushOutboxDispatchJob(dispatcher)

        assertThat(job.name).isEqualTo("question-push-outbox-dispatch")
        assertThat(job.run()).isEqualTo("processed=1")
    }

    private class CapturingPushPublisher(private val result: Boolean) : QuestionPushPublishPort {
        val requests = mutableListOf<QuestionPushRequest>()

        override fun publishPush(request: QuestionPushRequest): Boolean {
            requests += request
            return result
        }
    }

    private class ThrowingThenCapturingPushPublisher(private val failingRecordId: Long) : QuestionPushPublishPort {
        override fun publishPush(request: QuestionPushRequest): Boolean {
            if (request.recordId == failingRecordId) {
                throw IllegalStateException("boom")
            }
            return true
        }
    }
}
