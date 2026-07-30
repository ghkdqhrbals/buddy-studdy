package com.buddystudy.backend

import com.buddystudy.backend.common.adapter.outbound.persistence.StreamConsumerInboxRepository
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    properties = [
        "buddystudy.streams.enabled=false",
        "buddystudy.scheduler.enabled=false",
    ],
)
@Import(StreamConsumerInboxTransactionIntegrationTest.RollbackConfig::class)
class StreamConsumerInboxTransactionIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired
    private lateinit var inbox: StreamConsumerInboxRepository

    @Autowired
    private lateinit var rollbackWriter: RollbackWriter

    @Autowired
    private lateinit var database: DatabaseClient

    @Test
    fun `claim remains committed when the surrounding transaction rolls back`(): Unit = runBlocking {
        val eventId = "claim-rollback-${UUID.randomUUID()}"
        val group = "answer-grading"
        val now = Instant.parse("2032-07-30T00:00:00Z")

        assertThat(
            runCatching {
                rollbackWriter.claimAndFail(
                    eventId = eventId,
                    consumerGroup = group,
                    correlationId = UUID.randomUUID().toString(),
                    now = now,
                )
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalStateException::class.java)

        assertThat(inboxStatus(eventId, group)).isEqualTo("PROCESSING")
        assertThat(attemptStatus(eventId, group)).isEqualTo("PROCESSING")
    }

    @Test
    fun `failure remains committed when the surrounding transaction rolls back`(): Unit = runBlocking {
        val eventId = "failure-rollback-${UUID.randomUUID()}"
        val group = "answer-grading"
        val now = Instant.parse("2032-07-30T01:00:00Z")
        val claim = checkNotNull(
            inbox.claim(
                eventId = eventId,
                consumerGroup = group,
                correlationId = UUID.randomUUID().toString(),
                leaseDuration = Duration.ofMinutes(3),
                now = now,
                streamKey = "study.answer.grading.requested.v1",
            ),
        )

        assertThat(
            runCatching {
                rollbackWriter.markFailedAndFail(
                    claim = claim,
                    now = now.plusSeconds(1),
                )
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalStateException::class.java)

        assertThat(inboxStatus(eventId, group)).isEqualTo("FAILED")
        assertThat(attemptStatus(eventId, group)).isEqualTo("FAILED")
        assertThat(lastError(eventId, group)).isEqualTo(
            "IllegalStateException" to "forced handler failure",
        )
    }

    private suspend fun inboxStatus(eventId: String, consumerGroup: String): String =
        database.sql(
            """
            select status
            from stream_consumer_inbox
            where event_id = :eventId and consumer_group = :consumerGroup
            """.trimIndent(),
        )
            .bind("eventId", eventId)
            .bind("consumerGroup", consumerGroup)
            .map { row, _ -> row.get("status", String::class.java).orEmpty() }
            .one()
            .awaitSingle()

    private suspend fun attemptStatus(eventId: String, consumerGroup: String): String =
        database.sql(
            """
            select status
            from stream_consumer_inbox_attempts
            where event_id = :eventId and consumer_group = :consumerGroup and attempt = 1
            """.trimIndent(),
        )
            .bind("eventId", eventId)
            .bind("consumerGroup", consumerGroup)
            .map { row, _ -> row.get("status", String::class.java).orEmpty() }
            .one()
            .awaitSingle()

    private suspend fun lastError(eventId: String, consumerGroup: String): Pair<String, String> =
        database.sql(
            """
            select last_error_type, last_error
            from stream_consumer_inbox
            where event_id = :eventId and consumer_group = :consumerGroup
            """.trimIndent(),
        )
            .bind("eventId", eventId)
            .bind("consumerGroup", consumerGroup)
            .map { row, _ ->
                row.get("last_error_type", String::class.java).orEmpty() to
                    row.get("last_error", String::class.java).orEmpty()
            }
            .one()
            .awaitSingle()

    open class RollbackWriter(
        private val inbox: StreamConsumerInboxRepository,
    ) {
        @Transactional
        open suspend fun claimAndFail(
            eventId: String,
            consumerGroup: String,
            correlationId: String,
            now: Instant,
        ) {
            checkNotNull(
                inbox.claim(
                    eventId = eventId,
                    consumerGroup = consumerGroup,
                    correlationId = correlationId,
                    leaseDuration = Duration.ofMinutes(3),
                    now = now,
                    streamKey = "study.answer.grading.requested.v1",
                ),
            )
            throw IllegalStateException("force outer rollback")
        }

        @Transactional
        open suspend fun markFailedAndFail(claim: StreamInboxClaim, now: Instant) {
            check(
                inbox.markFailed(
                    claim = claim,
                    errorType = "IllegalStateException",
                    errorMessage = "forced handler failure",
                    now = now,
                ),
            )
            throw IllegalStateException("force outer rollback")
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RollbackConfig {
        @Bean
        fun rollbackWriter(inbox: StreamConsumerInboxRepository) = RollbackWriter(inbox)
    }
}
