package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.util.UUID

/** Executes the atomic failure guard against an isolated synthetic database. */
@Timeout(15)
class QuestionGenerationSagaFailureGuardTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///generation-failure-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val repository = QuestionGenerationSagaRepository(database)
    private val now = Instant.parse("2026-09-12T00:00:00Z")

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        database.sql("""
            create table question_generation_sagas (
                correlation_id varchar(64) primary key,
                status varchar(24) not null,
                question_id bigint,
                failed_step varchar(24),
                error_code varchar(80),
                error_message varchar(1000),
                quota_refunded_at timestamp(6),
                updated_at timestamp(6)
            )
        """.trimIndent()).fetch().rowsUpdated().awaitSingle()
    }

    @Test
    fun `generation failure cannot overwrite a commit observed after its earlier read`() = runBlocking<Unit> {
        for (committedStatus in listOf("TRANSLATING", "COMPLETED")) {
            insert(committedStatus, "GENERATING")
            // Model a generation worker's stale read before another transaction
            // committed the accepted question and its translation outboxes.
            assertThat(status(committedStatus)).isEqualTo("GENERATING")
            database.sql("update question_generation_sagas set status = :status, question_id = 42 where correlation_id = :id")
                .bind("status", committedStatus).bind("id", committedStatus).fetch().rowsUpdated().awaitSingle()

            assertThat(fail(committedStatus, QuestionGenerationStep.GENERATING)).isFalse()
            assertThat(status(committedStatus)).isEqualTo(committedStatus)
            val saved = database.sql("select question_id, quota_refunded_at, failed_step from question_generation_sagas where correlation_id = :id")
                .bind("id", committedStatus).fetch().one().awaitSingle()
            assertThat(saved["question_id"]).isEqualTo(42L)
            assertThat(saved["quota_refunded_at"]).isNull()
            assertThat(saved["failed_step"]).isNull()
        }
    }

    @Test
    fun `uncommitted generation and actual translation failures retain their rollback transitions`() = runBlocking<Unit> {
        for (initial in listOf("QUEUED", "GENERATING")) {
            insert(initial, initial)
            assertThat(fail(initial, QuestionGenerationStep.GENERATING)).isTrue()
            assertThat(status(initial)).isEqualTo("FAILED")
        }
        insert("translation", "TRANSLATING")
        assertThat(fail("translation", QuestionGenerationStep.TRANSLATING)).isTrue()
        assertThat(status("translation")).isEqualTo("FAILED")
    }

    private suspend fun insert(id: String, status: String) {
        database.sql("insert into question_generation_sagas (correlation_id, status) values (:id, :status)")
            .bind("id", id).bind("status", status).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun status(id: String): String = database.sql("select status from question_generation_sagas where correlation_id = :id")
        .bind("id", id).map { row, _ -> row.get("status", String::class.java)!! }.one().awaitSingle()

    private suspend fun fail(id: String, step: QuestionGenerationStep): Boolean = repository.markFailed(
        id, step, "QUESTION_GENERATION_FAILED", "Synthetic failure.", null, now,
    )
}
