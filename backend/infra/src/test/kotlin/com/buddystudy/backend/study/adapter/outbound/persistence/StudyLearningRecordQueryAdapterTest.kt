package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.study.application.model.StudyLearningRecordKey
import com.buddystudy.backend.study.application.model.StudyLearningRecordScope
import com.buddystudy.backend.study.application.model.StudyLearningRecordSource
import com.buddystudy.backend.study.application.model.StudyLearningRecordsCursor
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Executes the production read SQL against an isolated synthetic H2 schema, never a configured DB. */
@Timeout(15)
class StudyLearningRecordQueryAdapterTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///learning-record-keys-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val adapter = StudyLearningRecordQueryAdapter(database)

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        // timestamp(6), not timestamp-with-zone: production query maps MySQL UTC DATETIME to LocalDateTime.
        for (schema in listOf(
            """
            create table studies (
                id bigint primary key, user_id bigint not null, parent_study_id bigint,
                topic varchar(255) not null
            )
            """,
            """
            create table questions (
                id bigint primary key, user_id bigint not null, study_id bigint,
                topic varchar(255) not null, score integer, is_public boolean not null default false,
                record_type varchar(24) not null default 'QUESTION', voice_record_id bigint unique,
                status varchar(24) not null default 'graded',
                deleted_at timestamp(6), skipped_at timestamp(6),
                answered_at timestamp(6), created_at timestamp(6) not null
            )
            """,
            """
            create table voice_tutor_sessions (
                id varchar(64) primary key, user_id bigint not null, ended_at timestamp(6)
            )
            """,
            """
            create table voice_study_learning_records (
                id bigint primary key, user_id bigint not null, session_id varchar(64) not null,
                study_id bigint not null
            )
            """,
        )) database.sql(schema.trimIndent()).fetch().rowsUpdated().awaitSingle()
    }

    @Test
    fun `union orders by answer time then source then numeric ID while preserving identical source IDs`(): Unit = runBlocking {
        node(10)
        session()
        question(1, createdAt = AT.minusSeconds(100), answeredAt = AT.plusSeconds(10))
        voice(1, at = AT.plusSeconds(5))
        question(9)
        question(10, answeredAt = null)
        voice(9)
        voice(10)
        question(77, createdAt = AT.minusSeconds(1), answeredAt = null)

        val result = page()

        assertThat(result.labels()).containsExactly("QUESTION:1", "VOICE_TUTOR:1", "QUESTION:10", "QUESTION:9", "VOICE_TUTOR:10", "VOICE_TUTOR:9", "QUESTION:77")
        assertThat(result.map { it.createdAt }).containsExactly(AT.plusSeconds(10), AT.plusSeconds(5), AT, AT, AT, AT, AT.minusSeconds(1))
        assertThat(result.map { it.studyId }).containsOnly(10L)
    }

    @Test
    fun `exact node includes only actual study IDs and never infers linkage from a matching topic`(): Unit = runBlocking {
        node(10, topic = "Repeated title")
        node(11, parent = 10, topic = "Repeated title")
        node(12, topic = "Repeated title")
        session()
        question(10, topic = "An older saved title")
        voice(10, topic = "An older saved title")
        question(11, studyId = 11, topic = "Repeated title")
        voice(11, studyId = 11, topic = "Repeated title")
        question(12, studyId = 12, topic = "Repeated title")
        voice(12, studyId = 12, topic = "Repeated title")
        question(13, studyId = null, topic = "Repeated title")

        assertThat(page().labels()).containsExactly("QUESTION:10", "VOICE_TUTOR:10")
        assertThat(page(studyId = 11).labels()).containsExactly("QUESTION:11", "VOICE_TUTOR:11")
        assertThat(page(studyId = 12).labels()).containsExactly("QUESTION:12", "VOICE_TUTOR:12")
    }

    @Test
    fun `subtree includes owned descendants but cannot traverse a foreign-owner parent or a namesake root`(): Unit = runBlocking {
        node(10)
        node(11, parent = 10)
        node(12, parent = 11)
        node(13, parent = 10)
        node(14, parent = 10, userId = 99)
        node(15, parent = 14) // Even owned rows cannot be reached through somebody else's parent.
        node(20)
        session()
        session("other", userId = 99)
        for (id in listOf(10L, 11L, 12L, 13L, 15L, 20L)) {
            question(id, studyId = id)
            voice(id, studyId = id)
        }
        question(14, studyId = 14, userId = 99)
        voice(14, studyId = 14, userId = 99, sessionId = "other")

        val root = page(scope = StudyLearningRecordScope.SUBTREE)
        assertThat(root.labels()).containsExactly("QUESTION:13", "QUESTION:12", "QUESTION:11", "QUESTION:10", "VOICE_TUTOR:13", "VOICE_TUTOR:12", "VOICE_TUTOR:11", "VOICE_TUTOR:10")
        assertThat(root.map { it.studyId }.toSet()).containsExactlyInAnyOrder(10L, 11L, 12L, 13L)
        val child = page(studyId = 11, scope = StudyLearningRecordScope.SUBTREE)
        assertThat(child.labels()).containsExactly("QUESTION:12", "QUESTION:11", "VOICE_TUTOR:12", "VOICE_TUTOR:11")
        assertThat(child.map { it.studyId }.toSet()).containsExactlyInAnyOrder(11L, 12L)
    }

    @Test
    fun `both union branches enforce owner and voice rows require the same owned ended session`(): Unit = runBlocking {
        node(10)
        node(20, userId = 99)
        session()
        session("foreign", userId = 99)
        session("active", ended = false)
        question(1)
        question(2, userId = 99)
        question(3, studyId = 20)
        question(4, studyId = 20, userId = 99)
        voice(1)
        voice(2, userId = 99, sessionId = "foreign")
        voice(3, sessionId = "foreign")
        voice(4, studyId = 20)
        voice(5, sessionId = "missing-session")
        voice(6, sessionId = "active")

        assertThat(page().labels()).containsExactly("QUESTION:1", "VOICE_TUTOR:1")
        assertThat(page(userId = 99, studyId = 10)).isEmpty()
        assertThat(page(studyId = 20)).isEmpty()
        assertThat(page(studyId = 999)).isEmpty()
        assertThat(page(userId = 99, studyId = 20).labels()).containsExactly("QUESTION:4")
        assertThat(page(userId = 99, studyId = 10, scope = StudyLearningRecordScope.SUBTREE)).isEmpty()
    }

    @Test
    fun `deleted skipped and pending questions are excluded without hiding unscored private voice history`(): Unit = runBlocking {
        node(10)
        session()
        question(1)
        question(2, score = 0)
        question(3, score = null)
        question(4, deleted = true)
        question(5, skipped = true)
        question(6, skipped = true, score = null)
        voice(1, score = null)
        voice(2, score = 0)

        assertThat(page().labels()).containsExactly("QUESTION:2", "QUESTION:1", "VOICE_TUTOR:2", "VOICE_TUTOR:1")
    }

    @Test
    fun `canonical deletion and missing or inconsistent type linkage hide voice rows without changing legacy cursor identities`(): Unit = runBlocking {
        node(10)
        session()
        question(1)
        voice(1)
        voice(2)
        voice(3)
        voice(4)
        database.sql("update questions set deleted_at = created_at where voice_record_id = 2").fetch().rowsUpdated().awaitSingle()
        database.sql("delete from questions where voice_record_id = 3").fetch().rowsUpdated().awaitSingle()
        database.sql("update questions set record_type = 'QUESTION', score = null where voice_record_id = 4").fetch().rowsUpdated().awaitSingle()

        assertThat(page().labels()).containsExactly("QUESTION:1", "VOICE_TUTOR:1")
        val afterOrdinary = page(cursor = StudyLearningRecordsCursor(AT, StudyLearningRecordSource.QUESTION, 1))
        assertThat(afterOrdinary.labels()).containsExactly("VOICE_TUTOR:1")
    }

    @Test
    fun `keyset traverses timestamp ties without duplicates after anchor deletion and concurrent newer insertion`(): Unit = runBlocking {
        node(10)
        session()
        question(10)
        question(9)
        voice(10)
        voice(9)
        question(2, createdAt = AT.minusSeconds(1))
        val first = page(limit = 1).single()
        assertThat(first.source).isEqualTo(StudyLearningRecordSource.QUESTION)
        assertThat(first.recordId).isEqualTo(10)

        // Test-fixture mutations only: removing the cursor's row must not require an offset or a re-read.
        database.sql("delete from questions where id = 10 and user_id = 7").fetch().rowsUpdated().awaitSingle()
        question(11)
        voice(11, at = AT.plusSeconds(1))
        val seen = mutableListOf(first)
        var cursor = first.cursor()
        var exhausted = false
        repeat(5) {
            if (!exhausted) {
                val batch = page(limit = 1, cursor = cursor)
                if (batch.isEmpty()) exhausted = true
                else {
                    assertThat(batch).hasSize(1)
                    seen += batch
                    cursor = batch.last().cursor()
                }
            }
        }

        assertThat(exhausted).isTrue()
        assertThat(seen.labels()).containsExactly("QUESTION:10", "QUESTION:9", "VOICE_TUTOR:10", "VOICE_TUTOR:9", "QUESTION:2")
        assertThat(seen.labels().toSet()).hasSize(5)
    }

    @Test
    fun `SQL page size remains bounded including the single application lookahead`(): Unit = runBlocking {
        node(10)
        for (id in 1L..105L) question(id)

        assertThat(page(limit = Int.MIN_VALUE).map { it.recordId }).containsExactly(105L)
        assertThat(page(limit = 0).map { it.recordId }).containsExactly(105L)
        assertThat(page(limit = 31)).hasSize(31)
        val maximum = page(limit = Int.MAX_VALUE)
        assertThat(maximum).hasSize(101)
        assertThat(maximum.map { it.recordId }).containsExactlyElementsOf((105L downTo 5L).toList())
        assertThat(page(limit = 31, cursor = maximum.last().cursor()).map { it.recordId }).containsExactly(4L, 3L, 2L, 1L)
    }

    private suspend fun page(userId: Long = 7, studyId: Long = 10, scope: StudyLearningRecordScope = StudyLearningRecordScope.NODE, limit: Int = 30, cursor: StudyLearningRecordsCursor? = null) =
        adapter.pageKeys(userId, studyId, scope, limit, cursor)

    private suspend fun node(id: Long, parent: Long? = null, userId: Long = 7, topic: String = "Repeated title") {
        database.sql("insert into studies(id, user_id, parent_study_id, topic) values(:id, :userId, :parent, :topic)")
            .bind("id", id).bind("userId", userId).nullable("parent", parent, java.lang.Long::class.java).bind("topic", topic)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun session(id: String = "synthetic-ended-session", userId: Long = 7, ended: Boolean = true) {
        database.sql("insert into voice_tutor_sessions(id, user_id, ended_at) values(:id, :userId, :endedAt)")
            .bind("id", id).bind("userId", userId)
            .nullable("endedAt", if (ended) AT.utc() else null, LocalDateTime::class.java)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun question(
        id: Long, studyId: Long? = 10, userId: Long = 7, createdAt: Instant = AT, answeredAt: Instant? = createdAt,
        score: Int? = 80, deleted: Boolean = false, skipped: Boolean = false, topic: String = "Repeated title",
        recordType: String = "QUESTION", voiceRecordId: Long? = null,
    ) {
        database.sql(
            """
            insert into questions(id, user_id, study_id, topic, score, created_at, answered_at, deleted_at, skipped_at, record_type, voice_record_id, status)
            values(:id, :userId, :studyId, :topic, :score, :createdAt, :answeredAt, :deletedAt, :skippedAt, :recordType, :voiceRecordId, :status)
            """.trimIndent(),
        ).bind("id", id).bind("userId", userId).nullable("studyId", studyId, java.lang.Long::class.java)
            .bind("topic", topic).nullable("score", score, java.lang.Integer::class.java).bind("createdAt", createdAt.utc())
            .nullable("answeredAt", answeredAt?.utc(), LocalDateTime::class.java)
            .nullable("deletedAt", if (deleted) AT.utc() else null, LocalDateTime::class.java)
            .nullable("skippedAt", if (skipped) AT.utc() else null, LocalDateTime::class.java)
            .bind("recordType", recordType).nullable("voiceRecordId", voiceRecordId, java.lang.Long::class.java)
            .bind("status", if (recordType == "VOICE_TUTOR") "completed" else "graded")
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun voice(
        id: Long, studyId: Long = 10, userId: Long = 7, sessionId: String = "synthetic-ended-session",
        at: Instant = AT, topic: String = "Repeated title", score: Int? = null,
    ) {
        database.sql(
            """
            insert into voice_study_learning_records(id, user_id, session_id, study_id)
            values(:id, :userId, :sessionId, :studyId)
            """.trimIndent(),
        ).bind("id", id).bind("userId", userId).bind("sessionId", sessionId).bind("studyId", studyId)
            .fetch().rowsUpdated().awaitSingle()
        question(
            id = 1_000_000 + id, studyId = studyId, userId = userId, createdAt = at, answeredAt = null,
            topic = topic, score = score, recordType = "VOICE_TUTOR", voiceRecordId = id,
        )
    }

    private fun DatabaseClient.GenericExecuteSpec.nullable(name: String, value: Any?, type: Class<*>) =
        if (value == null) bindNull(name, type) else bind(name, value)

    private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
    private fun List<StudyLearningRecordKey>.labels() = map { "${it.source}:${it.recordId}" }
    private fun StudyLearningRecordKey.cursor() = StudyLearningRecordsCursor(createdAt, source, recordId)

    private companion object {
        val AT: Instant = Instant.parse("2026-08-31T03:04:05.123456Z")
    }
}
