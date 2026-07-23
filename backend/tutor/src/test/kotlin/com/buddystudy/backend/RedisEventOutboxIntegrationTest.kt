package com.buddystudy.backend

import com.buddystudy.backend.common.application.outbox.QuestionCreatedOutboxEvent
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxPort
import com.buddystudy.backend.common.application.outbox.RedisOutboxEventType
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@SpringBootTest
@Import(RedisEventOutboxIntegrationTest.RollbackConfig::class)
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.analytics.datasource.database-name=",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ],
)
class RedisEventOutboxIntegrationTest : PostgresIntegrationTestSupport() {
    @Autowired lateinit var outbox: RedisEventOutboxPort
    @Autowired lateinit var rollbackWriter: RollbackWriter

    @Test
    fun `event type scopes idempotency key and retry remains claimable`(): Unit = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val eventId = "question-created-$suffix"
        val now = Instant.parse("2030-07-23T00:00:00Z")

        val questionId = outbox.appendQuestionCreated(
            QuestionCreatedOutboxEvent(
                eventId = eventId,
                questionId = 42,
                language = "ko",
                createdAt = now,
            ),
        )
        val duplicateQuestionId = outbox.appendQuestionCreated(
            QuestionCreatedOutboxEvent(
                eventId = eventId,
                questionId = 42,
                language = "ko",
                createdAt = now,
            ),
        )
        val notificationId = outbox.appendNotification(
            NotificationRequestCommand(
                eventId = eventId,
                userId = 1,
                title = "BuddyStudy",
                body = "Question",
            ),
            createdAt = now,
        )

        assertThat(duplicateQuestionId).isEqualTo(questionId)
        assertThat(notificationId).isNotEqualTo(questionId)

        val claimed = outbox.claimBatch(
            now = now,
            staleBefore = now.minusSeconds(120),
            limit = 100,
        ).filter { it.eventId == eventId }
        assertThat(claimed.map { it.eventType })
            .containsExactlyInAnyOrder(
                RedisOutboxEventType.QUESTION_CREATED,
                RedisOutboxEventType.NOTIFICATION_REQUESTED,
            )

        val published = claimed.first()
        val retry = claimed.last()
        assertThat(outbox.markPublished(published.id, now)).isTrue()
        assertThat(
            outbox.markRetry(
                id = retry.id,
                attempts = 1,
                nextAttemptAt = now.plusSeconds(30),
                error = "temporary",
                updatedAt = now,
            ),
        ).isTrue()

        assertThat(
            outbox.claimBatch(
                now = now.plusSeconds(29),
                staleBefore = now.minusSeconds(120),
                limit = 100,
            ).none { it.eventId == eventId },
        ).isTrue()
        assertThat(
            outbox.claimBatch(
                now = now.plusSeconds(30),
                staleBefore = now.minusSeconds(90),
                limit = 100,
            ).filter { it.eventId == eventId }.map { it.id },
        ).containsExactly(retry.id)
    }

    @Test
    fun `outbox append rolls back with its surrounding reactive transaction`(): Unit = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val eventId = "rolled-back-$suffix"
        val now = Instant.parse("2031-07-23T00:00:00Z")

        assertThat(
            runCatching { rollbackWriter.appendAndFail(eventId, now) }.exceptionOrNull(),
        ).isInstanceOf(IllegalStateException::class.java)

        assertThat(
            outbox.claimBatch(
                now = now,
                staleBefore = now.minusSeconds(120),
                limit = 100,
            ).none { it.eventId == eventId },
        ).isTrue()
    }

    open class RollbackWriter(
        private val outbox: RedisEventOutboxPort,
    ) {
        @Transactional
        open suspend fun appendAndFail(eventId: String, now: Instant) {
            outbox.appendQuestionCreated(
                QuestionCreatedOutboxEvent(
                    eventId = eventId,
                    questionId = 99,
                    language = "ko",
                    createdAt = now,
                ),
            )
            throw IllegalStateException("force rollback")
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RollbackConfig {
        @Bean
        fun rollbackWriter(outbox: RedisEventOutboxPort) = RollbackWriter(outbox)
    }
}
