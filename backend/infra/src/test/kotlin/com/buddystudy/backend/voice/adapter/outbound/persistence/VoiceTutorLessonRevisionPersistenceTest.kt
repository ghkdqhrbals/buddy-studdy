package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Production adapter against isolated H2 only; not a MySQL locking/migration equivalence claim. */
class VoiceTutorLessonRevisionPersistenceTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///voice-revision-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val adapter = VoiceTutorPersistenceAdapter(database)
    private val now = Instant.parse("2026-09-01T00:00:00Z")

    @BeforeEach
    fun schema(): Unit = runBlocking {
        execute("create table studies (id bigint primary key)")
        execute("insert into studies (id) values (42), (43)")
        execute("""
            create table voice_tutor_sessions (
                id varchar(36) primary key, user_id bigint not null,
                study_id bigint null references studies(id) on delete set null, accepted_study_id bigint null,
                idempotency_key varchar(191) not null default 'fixture', provider_session_id varchar(191) null,
                status varchar(24) not null default 'ACTIVE', result_status varchar(24) not null default 'PENDING',
                language varchar(8) not null default 'ko', model varchar(128) not null default 'gpt-realtime',
                voice varchar(64) not null default 'marin', topic_snapshot varchar(255) not null default 'Redis',
                difficulty_snapshot int not null default 3,
                period_started_at timestamp not null, period_ends_at timestamp not null,
                reserved_seconds int not null default 3600, charged_seconds int not null default 0,
                max_session_seconds int not null default 3600, hard_ends_at timestamp not null,
                connected_at timestamp null, relay_heartbeat_at timestamp null,
                accepted_audio_bytes bigint not null default 0, recording_consented_at timestamp null,
                recording_consent_version varchar(64) null, ended_at timestamp null, finalized_at timestamp null,
                end_reason varchar(64) null, failure_code varchar(64) null, failure_message varchar(1000) null,
                created_at timestamp not null, updated_at timestamp not null
            )
        """.trimIndent())
        execute("""
            create table voice_tutor_transcript_turns (
                id bigint auto_increment primary key,
                session_id varchar(36) not null references voice_tutor_sessions(id) on delete cascade,
                provider_item_id varchar(191) not null, role varchar(16) not null, transcript text not null,
                sequence_number bigint not null, occurred_at timestamp not null, created_at timestamp not null,
                lesson_revision bigint not null default 0 check (lesson_revision >= -1),
                unique (session_id, provider_item_id, role)
            )
        """.trimIndent())
        seedSession("owned", userId = 7, studyId = 42)
        seedSession("foreign", userId = 8, studyId = 43)
    }

    @Test
    fun `append and read preserve each original transcript and its response time lesson revision`(): Unit = runBlocking {
        assertThat(append("question-old", VoiceTutorTranscriptRole.TUTOR, "기존 난도의 질문입니다.", 0)).isTrue()
        assertThat(append("answer-old", VoiceTutorTranscriptRole.USER, "기존 질문에 대한 제 답변입니다.", 0)).isTrue()
        assertThat(append("question-new", VoiceTutorTranscriptRole.TUTOR, "변경된 난도의 새 질문입니다.", 3)).isTrue()

        val turns = adapter.transcript(7, "owned", 4_000)

        assertThat(turns.map { it.lessonRevision }).containsExactly(0L, 0L, 3L)
        assertThat(turns.map { it.transcript }).containsExactly(
            "기존 난도의 질문입니다.", "기존 질문에 대한 제 답변입니다.", "변경된 난도의 새 질문입니다.",
        )
        assertThat(turns.map { it.sequenceNumber }).containsExactly(1L, 2L, 3L)
        assertThat(turns.map { it.occurredAt }.distinct()).containsExactly(now)
    }

    @Test
    fun `a replay at a newer revision cannot rewrite the first saved question or its level evidence`(): Unit = runBlocking {
        assertThat(append("question", VoiceTutorTranscriptRole.TUTOR, "원래 질문", 1)).isTrue()
        assertThat(append("question", VoiceTutorTranscriptRole.TUTOR, "바꾸면 안 되는 재전송", 5)).isFalse()

        val stored = adapter.transcript(7, "owned", 4_000).single()

        assertThat(stored.transcript).isEqualTo("원래 질문")
        assertThat(stored.lessonRevision).isEqualTo(1)
    }

    @Test
    fun `missing revision evidence keeps the exact source as unassigned rather than revision zero`(): Unit = runBlocking {
        assertThat(append("unknown-epoch", VoiceTutorTranscriptRole.USER, "메타데이터와 무관하게 보존할 원문", -1)).isTrue()

        val stored = adapter.transcript(7, "owned", 4_000).single()

        assertThat(stored.lessonRevision).isEqualTo(-1)
        assertThat(stored.transcript).isEqualTo("메타데이터와 무관하게 보존할 원문")
        assertThat(adapter.transcript(8, "owned", 4_000)).isEmpty()
    }

    @Test
    fun `legacy database default remains zero while owner and terminal state still gate new transcripts`(): Unit = runBlocking {
        database.sql("""
            insert into voice_tutor_transcript_turns
                (session_id, provider_item_id, role, transcript, sequence_number, occurred_at, created_at)
            values ('owned', 'legacy', 'TUTOR', 'Legacy question', 1, :now, :now)
        """.trimIndent()).bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
        assertThat(adapter.transcript(7, "owned", 4_000).single().lessonRevision).isZero()
        assertThat(adapter.appendTranscript(8, "owned", "foreign-write", VoiceTutorTranscriptRole.USER,
            "not owned", now, 4_000, 10, lessonRevision = 2)).isFalse()
        execute("update voice_tutor_sessions set status = 'COMPLETED' where id = 'owned'")
        assertThat(append("late-write", VoiceTutorTranscriptRole.USER, "late source", 2)).isFalse()
        assertThat(adapter.transcript(7, "owned", 4_000)).hasSize(1)
    }

    @Test
    fun `accepted study identity survives live study deletion and remains owner scoped`(): Unit = runBlocking {
        val before = adapter.findSession(7, "owned")!!
        assertThat(before.studyId).isEqualTo(42)
        assertThat(before.acceptedStudyId).isEqualTo(42)

        execute("delete from studies where id = 42")

        val after = adapter.findSession(7, "owned")!!
        assertThat(after.studyId).isNull()
        assertThat(after.acceptedStudyId).isEqualTo(42)
        assertThat(after.topic).isEqualTo(before.topic)
        assertThat(after.difficulty).isEqualTo(before.difficulty)
        assertThat(adapter.findSession(8, "owned")).isNull()
        assertThat(adapter.findSession(8, "foreign")!!.acceptedStudyId).isEqualTo(43)
    }

    @Test
    fun `reaching the storage turn limit returns false without inserting a consent bearing source`(): Unit = runBlocking {
        assertThat(append("first", VoiceTutorTranscriptRole.USER, "기존에 저장한 응답", 1)).isTrue()

        val stored = adapter.appendTranscript(
            7, "owned", "over-capacity", VoiceTutorTranscriptRole.USER,
            "확인으로 승격하면 안 되는 미저장 응답", now, 4_000, 1, lessonRevision = 2,
        )

        assertThat(stored).isFalse()
        assertThat(adapter.transcript(7, "owned", 4_000).map { it.providerItemId }).containsExactly("first")
        assertThat(adapter.findSession(7, "owned")!!.status.name).isEqualTo("ACTIVE")
    }

    private suspend fun append(id: String, role: VoiceTutorTranscriptRole, text: String, revision: Long): Boolean =
        adapter.appendTranscript(7, "owned", id, role, text, now, 4_000, 20, lessonRevision = revision)

    private suspend fun seedSession(id: String, userId: Long, studyId: Long) {
        database.sql("""
            insert into voice_tutor_sessions
                (id, user_id, study_id, accepted_study_id, period_started_at, period_ends_at, hard_ends_at, created_at, updated_at)
            values (:id, :user, :study, :study, :now, :until, :until, :now, :now)
        """.trimIndent()).bind("id", id).bind("user", userId).bind("study", studyId)
            .bind("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
            .bind("until", LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC))
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun execute(sql: String) { database.sql(sql).fetch().rowsUpdated().awaitSingle() }
}
