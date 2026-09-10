package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.common.adapter.outbound.transaction.ReactiveAfterCommitAdapter
import com.buddystudy.backend.study.application.model.QuestionGenerationSaga
import com.buddystudy.backend.study.application.model.QuestionGenerationSource
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.port.outbound.NoopStudyLearningProgressSignalPort
import com.buddystudy.backend.study.application.port.outbound.StudyLearningProgressSignalPort
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import com.buddystudy.study.domain.entity.QuestionStatus
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.util.UUID

/** Real repository SQL and transaction callbacks against isolated H2, no configured DB or Redis. */
@Timeout(15)
class StudyLearningProgressNotificationsTest {
    private val connections = ConnectionFactories.get(
        "r2dbc:h2:mem:///learning-progress-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    )
    private val database = DatabaseClient.create(connections)
    private val transactions = TransactionalOperator.create(R2dbcTransactionManager(connections))
    private val notifications = mutableListOf<String>()
    private val afterCommit = ReactiveAfterCommitAdapter()
    private val signals = object : StudyLearningProgressSignalPort {
        override suspend fun changed(correlationId: String) {
            afterCommit.execute { notifications += correlationId }
        }
        override fun changes(correlationId: String): Flow<Unit> = NoopStudyLearningProgressSignalPort.changes(correlationId)
    }
    private val generation = QuestionGenerationSagaRepository(database, signals)
    private val grading = AnswerGradingProgressRepository(database, signals)
    private val now = Instant.parse("2026-09-11T00:00:00Z")

    @BeforeEach
    fun prepare(): Unit = runBlocking {
        for (schema in listOf(
            """
            create table question_generation_sagas (
                correlation_id varchar(191) primary key, user_id bigint not null, study_id bigint not null,
                topic_id bigint not null, question_id bigint, source varchar(32), status varchar(32), current_step varchar(32),
                idempotency_key varchar(191), quota_period_started_at timestamp(6), quota_refunded_at timestamp(6),
                failed_step varchar(32), error_code varchar(80), error_message varchar(1000),
                created_at timestamp(6), updated_at timestamp(6), completed_at timestamp(6), rollback_completed_at timestamp(6),
                unique(user_id, idempotency_key)
            )
            """,
            """
            create table question_grading_events (
                id bigint auto_increment primary key, question_id bigint not null, user_id bigint not null,
                request_id varchar(191) not null, status varchar(32), question_status varchar(32),
                error_message varchar(255), created_at timestamp(6), unique(request_id, status)
            )
            """,
        )) database.sql(schema.trimIndent()).fetch().rowsUpdated().awaitSingle()
    }

    @Test
    fun `generation committed transitions wake exact correlation but stale updates do not`(): Unit = runBlocking {
        transactions.executeAndAwait {
            assertThat(generation.insert(saga("generation_1"))).isTrue()
            assertThat(generation.markGenerating("generation_1", now)).isTrue()
            assertThat(generation.markGenerating("generation_1", now)).isFalse()
            assertThat(generation.markTranslating("generation_1", 101, now)).isTrue()
            assertThat(generation.markCompleted("generation_1", now)).isTrue()
            assertThat(generation.markCompleted("generation_1", now)).isFalse()
            assertThat(notifications).isEmpty()
        }
        assertThat(notifications).containsExactly("generation_1", "generation_1", "generation_1", "generation_1")
        assertThat(generation.findByCorrelationId("generation_1")?.status).isEqualTo(QuestionGenerationStatus.COMPLETED)
    }

    @Test
    fun `generation failure and completed refund each notify after their durable write`(): Unit = runBlocking {
        generation.insert(saga("generation_failed"))
        notifications.clear()
        transactions.executeAndAwait {
            assertThat(generation.markFailed("generation_failed", QuestionGenerationStep.GENERATING,
                "SYNTHETIC", "private failure text", null, now)).isTrue()
            assertThat(generation.markRollbackCompleted("generation_failed", now)).isTrue()
            assertThat(generation.markRollbackCompleted("generation_failed", now)).isFalse()
            assertThat(notifications).isEmpty()
        }
        assertThat(notifications).containsExactly("generation_failed", "generation_failed")
        assertThat(generation.findByCorrelationId("generation_failed")?.rollbackCompletedAt).isEqualTo(now)
    }

    @Test
    fun `rollback never publishes a generation that is absent from canonical storage`(): Unit = runBlocking {
        try {
            transactions.executeAndAwait {
                generation.insert(saga("rolled_back"))
                generation.markGenerating("rolled_back", now)
                throw IllegalStateException("synthetic rollback")
            }
        } catch (_: IllegalStateException) {
            // Expected, the after-commit callbacks must never run.
        }
        assertThat(notifications).isEmpty()
        assertThat(generation.findByCorrelationId("rolled_back")).isNull()
    }

    @Test
    fun `grading publishes process request ID after event commit without record or private content`(): Unit = runBlocking {
        transactions.executeAndAwait {
            val first = grading.append(101, 7, "grading_1", AnswerGradingStatus.QUEUED, QuestionStatus.GRADING,
                null, now)
            val last = grading.append(101, 7, "grading_1", AnswerGradingStatus.FAILED, QuestionStatus.FAILED,
                "private result detail", now.plusSeconds(1))
            assertThat(last.id).isGreaterThan(first.id)
            assertThat(notifications).isEmpty()
        }
        assertThat(notifications).containsExactly("grading_1", "grading_1")
        assertThat(grading.findAfter(101, 7, "grading_1", 0, 10).map { it.status })
            .containsExactly(AnswerGradingStatus.QUEUED, AnswerGradingStatus.FAILED)
    }

    private fun saga(id: String) = QuestionGenerationSaga(
        correlationId = id, userId = 7, studyId = 42, topicId = 42, questionId = null,
        source = QuestionGenerationSource.MANUAL, status = QuestionGenerationStatus.QUEUED,
        currentStep = QuestionGenerationStep.QUEUED, idempotencyKey = id,
        quotaPeriodStartedAt = now, quotaRefundedAt = null, failedStep = null, errorCode = null, errorMessage = null,
        createdAt = now, updatedAt = now, completedAt = null,
    )
}
