package com.buddystuddy.backend

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.adapter.inbound.scheduler.QuestionPushOutboxDispatcher
import com.buddystuddy.backend.study.adapter.outbound.persistence.QuestionPushOutboxJpaRepository
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystuddy.study.domain.entity.QuestionPushOutboxEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystuddy-push-outbox;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "buddystuddy.scheduler.enabled=false",
        "buddystuddy.streams.enabled=false",
        "buddystuddy.crypto.master-key=test-master-key",
        "buddystuddy.auth.jwt-secret=test-jwt-secret",
        "spring.autoconfigure.exclude=com.redisstream.RedisStreamCoordinatorAutoConfiguration,com.redisstream.producer.ProducerRoutingAutoConfiguration,com.redisstream.consumer.CoordinatorConsumerAutoConfiguration",
    ]
)
class QuestionPushOutboxDispatcherTest {
    @Autowired lateinit var outbox: QuestionPushOutboxJpaRepository

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
            BuddyStuddyProperties(
                scheduler = BuddyStuddyProperties.Scheduler(enabled = true),
                streams = BuddyStuddyProperties.Streams(enabled = true),
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
            BuddyStuddyProperties(
                scheduler = BuddyStuddyProperties.Scheduler(enabled = true),
                streams = BuddyStuddyProperties.Streams(enabled = true),
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

    private class CapturingPushPublisher(private val result: Boolean) : QuestionPushPublishPort {
        val requests = mutableListOf<QuestionPushRequest>()

        override fun publishPush(request: QuestionPushRequest): Boolean {
            requests += request
            return result
        }
    }
}
