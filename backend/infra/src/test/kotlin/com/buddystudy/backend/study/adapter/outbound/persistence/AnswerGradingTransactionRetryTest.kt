package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.common.application.outbox.OutboxPublishSummary
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.study.application.port.inbound.AnswerGradingWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.CompletedAnswerGrading
import com.buddystudy.backend.study.application.port.outbound.AiGradingRubric
import com.buddystudy.backend.study.application.port.outbound.AiGradingStage
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.AnswerGradingService
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import com.buddystudy.study.domain.entity.QuestionEntity
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Real isolated transaction rollback; the provider and authenticated writer ports remain fakes. */
@Timeout(15)
class AnswerGradingTransactionRetryTest {
    @Test
    fun `transient projection failure rolls back progress and retries its transaction without regrading`() = runBlocking<Unit> {
        val f = Fixture(failures = mapOf("transition" to 1))
        f.prepare()

        f.service.process(f.event, "study.answer.grade.v1")

        assertThat(f.attempts).containsExactlyEntriesOf(linkedMapOf("transition" to 2, "complete" to 1))
        assertThat(f.committed()).containsExactly("transition", "complete")
        assertThat(f.projected()).containsExactly("transition", "complete")
        assertThat(f.providerCalls).isEqualTo(1)
        assertThat(f.published).containsExactly(f.outbox)
        assertThat(f.succeeded).isEqualTo(1)
    }

    @Test
    fun `transient completion failure reuses the exact grade and publishes one committed outbox`() = runBlocking<Unit> {
        val f = Fixture(failures = mapOf("complete" to 1))
        f.prepare()

        f.service.process(f.event, "study.answer.grade.v1")

        assertThat(f.attempts).containsEntry("complete", 2)
        assertThat(f.completedGrades).hasSize(2).allSatisfy { assertThat(it).isSameAs(f.grade) }
        assertThat(f.committed()).containsExactly("transition", "complete")
        assertThat(f.providerCalls).isEqualTo(1)
        assertThat(f.published).containsExactly(f.outbox)
        assertThat(f.succeeded).isEqualTo(1)
    }

    @Test
    fun `exhausted progress failure is bounded and leaves no rolled back partial writes`() = runBlocking<Unit> {
        val f = Fixture(failures = mapOf("transition" to 4))
        f.prepare()

        f.service.process(f.event, "study.answer.grade.v1")

        assertThat(f.attempts).containsExactlyEntriesOf(linkedMapOf("transition" to 3, "fail" to 1))
        assertThat(f.committed()).containsExactly("fail")
        assertThat(f.projected()).containsExactly("fail")
        assertThat(f.providerCalls).isEqualTo(1)
        assertThat(f.published).isEmpty()
        assertThat(f.succeeded).isEqualTo(1)
    }

    @Test
    fun `exhausted completion failure does not acknowledge or claim a successful grade`() = runBlocking<Unit> {
        val f = Fixture(failures = mapOf("complete" to 4))
        f.prepare()

        val result = runCatching { f.service.process(f.event, "study.answer.grade.v1") }

        assertThat(result.exceptionOrNull()).isInstanceOf(TransientDataAccessResourceException::class.java)
        assertThat(f.attempts).containsExactlyEntriesOf(linkedMapOf("transition" to 1, "complete" to 3))
        assertThat(f.committed()).containsExactly("transition")
        assertThat(f.providerCalls).isEqualTo(1)
        assertThat(f.published).isEmpty()
        assertThat(f.succeeded).isZero()
    }

    @Test
    fun `failure marker transient write retries independently and never reruns the failed provider`() = runBlocking<Unit> {
        val f = Fixture(failures = mapOf("fail" to 1), providerFailure = IllegalStateException("Synthetic provider failure"))
        f.prepare()

        f.service.process(f.event, "study.answer.grade.v1")

        assertThat(f.attempts).containsExactlyEntriesOf(linkedMapOf("transition" to 1, "fail" to 2))
        assertThat(f.committed()).containsExactly("transition", "fail")
        assertThat(f.projected()).containsExactly("transition", "fail")
        assertThat(f.providerCalls).isEqualTo(1)
        assertThat(f.published).isEmpty()
        assertThat(f.succeeded).isEqualTo(1)
    }

    @Test
    fun `nontransient write error and cancellation are never retried`() = runBlocking<Unit> {
        for (failure in listOf(IllegalStateException("Synthetic permanent write failure"), CancellationException("Synthetic cancellation"))) {
            val f = Fixture(failures = mapOf("transition" to 1), writeFailure = failure)
            f.prepare()

            val result = runCatching { f.service.process(f.event, "study.answer.grade.v1") }

            assertThat(f.attempts["transition"]).isEqualTo(1)
            assertThat(f.providerCalls).isEqualTo(1)
            if (failure is CancellationException) {
                // Coroutine stack-trace recovery may copy CancellationException
                // across the transaction boundary; cancellation, not object identity,
                // is the contract that must reach the caller unchanged.
                assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
                    .hasMessage(failure.message)
                assertThat(f.committed()).isEmpty()
                assertThat(f.succeeded).isZero()
            } else {
                assertThat(result.isSuccess).isTrue()
                assertThat(f.committed()).containsExactly("fail")
                assertThat(f.succeeded).isEqualTo(1)
            }
        }
    }

    private class Fixture(
        private val failures: Map<String, Int>,
        private val providerFailure: RuntimeException? = null,
        private val writeFailure: RuntimeException = TransientDataAccessResourceException("Synthetic projection deadlock"),
    ) {
        private val connections = ConnectionFactories.get(
            "r2dbc:h2:mem:///grading-retry-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
        )
        private val database = DatabaseClient.create(connections)
        private val transactions = TransactionalOperator.create(R2dbcTransactionManager(connections))
        val event = AnswerGradingRequestedEvent("grading-event", "grading-request", 124, 7, Instant.EPOCH)
        val grade = GradedAnswer(85, true, "Synthetic feedback", "Synthetic explanation")
        val outbox = OutboxReference(OutboxType.DOMAIN_EVENT, 99)
        val attempts = linkedMapOf<String, Int>()
        val completedGrades = mutableListOf<GradedAnswer>()
        val published = mutableListOf<OutboxReference>()
        var providerCalls = 0
        var succeeded = 0
        private val questions = mock(QuestionPort::class.java)
        private val properties = BuddyStudyProperties().apply { openai.userContentApiKey = "synthetic-test-key" }
        private val writer = object : AnswerGradingWriteUseCase by mock(AnswerGradingWriteUseCase::class.java) {
            override suspend fun transition(event: AnswerGradingRequestedEvent, status: AnswerGradingStatus, now: Instant): Boolean {
                assertThat(event).isEqualTo(this@Fixture.event)
                assertThat(status).isEqualTo(AnswerGradingStatus.ANALYZING_EVIDENCE)
                write("transition")
                return true
            }

            override suspend fun complete(event: AnswerGradingRequestedEvent, grade: GradedAnswer, now: Instant): CompletedAnswerGrading {
                assertThat(event).isEqualTo(this@Fixture.event)
                completedGrades += grade
                write("complete")
                return CompletedAnswerGrading(true, listOf(outbox))
            }

            override suspend fun fail(event: AnswerGradingRequestedEvent, errorMessage: String, now: Instant) {
                assertThat(event).isEqualTo(this@Fixture.event)
                write("fail")
            }
        }
        private val provider = object : OpenAIPort by mock(OpenAIPort::class.java) {
            override suspend fun gradeWithRubric(
                apiKey: String, model: String, question: String, answer: String, topic: String,
                level: Int, language: String, rubric: AiGradingRubric?, onProgress: suspend (AiGradingStage) -> Unit,
            ): GradedAnswer {
                providerCalls++
                onProgress(AiGradingStage.ANALYZING_EVIDENCE)
                providerFailure?.let { throw it }
                return grade
            }
        }
        private val inbox = object : StreamInboxPort by mock(StreamInboxPort::class.java) {
            override suspend fun claim(
                eventId: String, consumerGroup: String, correlationId: String,
                leaseDuration: Duration, now: Instant, streamKey: String,
            ) = StreamInboxClaim(eventId, consumerGroup, "claim", 1, streamKey)

            override suspend fun markSucceeded(claim: StreamInboxClaim, now: Instant): Boolean {
                succeeded++
                return true
            }
        }
        private val publisher = object : PublishOutboxUseCase {
            override suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary {
                published += references
                return OutboxPublishSummary(references.size, references.size, 0)
            }
        }
        val service = AnswerGradingService(properties, questions, mock(StudyPort::class.java),
            UserContentOpenAIKeyProvider(properties), provider, writer, inbox, publisher)

        suspend fun prepare() {
            database.sql("create table grading_writes (id bigint auto_increment primary key, operation varchar(32) not null unique)")
                .fetch().rowsUpdated().awaitSingle()
            database.sql("create table grading_projection (id bigint auto_increment primary key, operation varchar(32) not null unique)")
                .fetch().rowsUpdated().awaitSingle()
            `when`(questions.findByIdAndUserIdAndDeletedAtIsNull(event.recordId, event.userId)).thenReturn(
                QuestionEntity(id = event.recordId, userId = event.userId, question = "Synthetic saved question",
                    answer = "Synthetic submitted answer", topic = "Synthetic topic",
                    gradingRequestId = event.requestId, gradingStatus = AnswerGradingStatus.QUEUED),
            )
        }

        private suspend fun write(operation: String) {
            attempts[operation] = attempts.getOrDefault(operation, 0) + 1
            transactions.executeAndAwait {
                // Same unique key each attempt: retry can succeed only after the
                // failed attempt's entire transaction has rolled back.
                database.sql("insert into grading_writes(operation) values (:operation)")
                    .bind("operation", operation).fetch().rowsUpdated().awaitSingle()
                if (attempts.getValue(operation) <= failures.getOrDefault(operation, 0)) throw writeFailure
                database.sql("insert into grading_projection(operation) values (:operation)")
                    .bind("operation", operation).fetch().rowsUpdated().awaitSingle()
            }
        }

        suspend fun committed(): List<String> = operations("grading_writes")
        suspend fun projected(): List<String> = operations("grading_projection")
        private suspend fun operations(table: String): List<String> = database.sql("select operation from $table order by id")
            .map { row, _ -> row.get("operation", String::class.java)!! }.all().collectList().awaitSingle()
    }
}
