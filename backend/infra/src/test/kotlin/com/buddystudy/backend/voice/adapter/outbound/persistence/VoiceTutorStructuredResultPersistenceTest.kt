package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.voice.adapter.outbound.VoiceTutorExplorationJsonCodec
import com.buddystudy.backend.voice.application.model.toResponse
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorLearningExchange
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import java.time.LocalDateTime
import java.util.UUID

/** In-memory read/codec contract only; does not launch containers or connect to development data. */
class VoiceTutorStructuredResultPersistenceTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///voice-result-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val adapter = VoiceTutorPersistenceAdapter(database)
    private val now = LocalDateTime.parse("2026-08-31T10:00:00")

    @BeforeEach
    fun schema() = runBlocking<Unit> {
        execute("create table voice_tutor_sessions (id varchar(36) primary key, user_id bigint not null)")
        execute("""
            create table voice_tutor_results (
                session_id varchar(36) primary key, status varchar(24) not null, summary_markdown text null,
                strengths_json text null, improvements_json text null, next_steps_json text null,
                explorations_json text null, model varchar(128) null, prompt_version varchar(64) not null,
                error_message varchar(1000) null, created_at timestamp not null, updated_at timestamp not null
            )
        """.trimIndent())
        execute("insert into voice_tutor_sessions (id, user_id) values ('owned', 7), ('other', 8)")
    }

    @Test
    fun `legacy null JSON returns its existing summary with empty explorations`() = runBlocking<Unit> {
        seed("owned", null)

        val stored = adapter.result(7, "owned")!!

        assertThat(stored.summaryMarkdown).isEqualTo("Persisted learning summary")
        assertThat(stored.explorations).isEmpty()
        assertThat(stored.toResponse().explorations).isEmpty()
    }

    @Test
    fun `the JSON written by the production codec is read through the actual adapter into nested HTTP DTOs`() = runBlocking<Unit> {
        val exploration = VoiceTutorExploration("Redis eviction", 43, 7, "LRU와 LFU 차이를 탐구했습니다.", listOf(
            VoiceTutorLearningExchange(VoiceTutorExchangeKind.TUTOR_QUESTION, "LRU는 무엇인가요?", "최근 사용 기준입니다.",
                100, listOf("정책 기준 이해"), emptyList(), 101, listOf(102), listOf(103)),
            VoiceTutorLearningExchange(VoiceTutorExchangeKind.LEARNER_QUESTION, "LFU와 다른 점은요?", "빈도를 기준으로 합니다.",
                null, emptyList(), emptyList(), 104, listOf(105), emptyList()),
        ))
        seed("owned", VoiceTutorExplorationJsonCodec.encode(listOf(exploration)))

        val stored = adapter.result(7, "owned")!!
        val response = stored.toResponse()

        assertThat(stored.explorations).containsExactly(exploration)
        assertThat(response.explorations.single().difficulty).isEqualTo(7)
        assertThat(response.explorations.single().exchanges.map { it.score }).containsExactly(100, null)
        assertThat(response.explorations.single().exchanges.last().answerTurnIds).containsExactly(105L)
    }

    @Test
    fun `result lookup remains owner scoped including structured evidence`() = runBlocking<Unit> {
        seed("owned", "[]")
        seed("other", "[]")

        assertThat(adapter.result(8, "owned")).isNull()
        assertThat(adapter.result(7, "other")).isNull()
        assertThat(adapter.result(7, "missing")).isNull()
        assertThat(adapter.result(8, "other")).isNotNull()
    }

    private suspend fun seed(sessionId: String, explorations: String?) {
        var query = database.sql("""
            insert into voice_tutor_results (session_id, status, summary_markdown, strengths_json,
                improvements_json, next_steps_json, explorations_json, model, prompt_version, created_at, updated_at)
            values (:id, 'COMPLETED', 'Persisted learning summary', '[]', '[]', '[]', :explorations,
                'gpt-5.4', 'voice-tutor-summary-v2', :created, :updated)
        """.trimIndent()).bind("id", sessionId).bind("created", now).bind("updated", now)
        query = if (explorations == null) query.bindNull("explorations", String::class.java) else query.bind("explorations", explorations)
        query.fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun execute(sql: String) { database.sql(sql).fetch().rowsUpdated().awaitSingle() }
}
