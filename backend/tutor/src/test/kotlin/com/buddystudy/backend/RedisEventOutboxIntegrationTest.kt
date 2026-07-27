package com.buddystudy.backend

import com.buddystudy.backend.common.application.outbox.RedisEventOutboxPort
import com.buddystudy.backend.common.application.outbox.RedisOutboxEventType
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.profile.application.model.AccountWithdrawnEvent
import com.buddystudy.backend.profile.application.port.outbound.AccountWithdrawalEventPort
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
class RedisEventOutboxIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var outbox: RedisEventOutboxPort
    @Autowired lateinit var withdrawalEvents: AccountWithdrawalEventPort
    @Autowired lateinit var rollbackWriter: RollbackWriter

    @Test
    fun `notification idempotency and retry remain claimable`(): Unit = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val eventId = "notification-requested-$suffix"
        val now = Instant.parse("2030-07-23T00:00:00Z")

        fun command() =
            NotificationRequestCommand(
                eventId = eventId,
                userId = 1,
                title = "BuddyStudy",
                body = "Question",
            )
        val notificationId = outbox.appendNotification(
            command(),
            createdAt = now,
        )
        val duplicateNotificationId = outbox.appendNotification(
            command(),
            createdAt = now,
        )

        assertThat(duplicateNotificationId).isEqualTo(notificationId)

        val claimed = outbox.claimBatch(
            now = now,
            staleBefore = now.minusSeconds(120),
            limit = 100,
        ).filter { it.eventId == eventId }
        assertThat(claimed.map { it.eventType })
            .containsExactly(RedisOutboxEventType.NOTIFICATION_REQUESTED)

        val retry = claimed.single()
        assertThat(
            outbox.markRetry(
                id = retry.id,
                claimToken = retry.claimToken,
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
        val reclaimed = outbox.claimBatch(
                now = now.plusSeconds(30),
                staleBefore = now.minusSeconds(90),
                limit = 100,
            ).single { it.eventId == eventId }
        assertThat(reclaimed.id).isEqualTo(retry.id)
        assertThat(reclaimed.claimToken).isNotEqualTo(retry.claimToken)
        assertThat(outbox.markPublished(reclaimed.id, retry.claimToken, now.plusSeconds(30))).isFalse()
        assertThat(outbox.markPublished(reclaimed.id, reclaimed.claimToken, now.plusSeconds(30))).isTrue()
    }

    @Test
    fun `account withdrawal event is durably appended with a stable id`(): Unit = runBlocking {
        val userId = UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE)
        val now = Instant.parse("2030-07-24T00:00:00Z")
        val event = AccountWithdrawnEvent.create(
            userId = userId,
            deviceIds = listOf("device-a", "device-a", "device-b"),
            withdrawnAt = now,
        )

        val first = withdrawalEvents.append(event)
        val duplicate = withdrawalEvents.append(event)

        assertThat(duplicate).isEqualTo(first)
        val claimed = outbox.claimBatch(
            now = now,
            staleBefore = now.minusSeconds(120),
            limit = 100,
        ).single { it.eventId == event.eventId }
        assertThat(claimed.eventType).isEqualTo(RedisOutboxEventType.ACCOUNT_WITHDRAWN)
        assertThat(claimed.payloadJson).contains("\"userId\":$userId")
        assertThat(claimed.payloadJson).contains("\"deviceIds\":[\"device-a\",\"device-b\"]")
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
            outbox.appendNotification(
                NotificationRequestCommand(
                    eventId = eventId,
                    userId = 1,
                    title = "BuddyStudy",
                    body = "Question",
                ),
                createdAt = now,
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
