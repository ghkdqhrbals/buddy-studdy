package com.buddystudy.backend.voice.adapter.outbound.persistence

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.r2dbc.core.DatabaseClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID

/** Real V106 backfill SQL with a minimal H2 fixture; not proof of MySQL DDL or row-lock behavior. */
@Timeout(15)
class VoiceTutorLessonFocusMigrationTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///voice-focus-migration-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val migration = Files.readString(listOf(
        Path.of("../tutor/src/main/resources/db/migration-mysql/V106__voice_tutor_lesson_focuses.sql"),
        Path.of("tutor/src/main/resources/db/migration-mysql/V106__voice_tutor_lesson_focuses.sql"),
        Path.of("backend/tutor/src/main/resources/db/migration-mysql/V106__voice_tutor_lesson_focuses.sql"),
    ).first { Files.isRegularFile(it) })
    private val backfill = "insert into voice_tutor_lesson_focuses" +
        migration.substringAfter("insert into voice_tutor_lesson_focuses").substringBefore(';')
    private val at = LocalDateTime.parse("2026-09-01T03:04:05.123456")

    @BeforeEach
    fun schema(): Unit = runBlocking {
        execute("""
            create table voice_tutor_sessions (
                id varchar(36) primary key, study_id bigint, accepted_study_id bigint,
                created_at timestamp(6) not null
            )
        """.trimIndent())
        // H2's driver splits semicolons even inside COMMENT text. Remove only MySQL
        // table options/COMMENT metadata; the exact INSERT-SELECT remains unchanged.
        val ddl = ("create table voice_tutor_lesson_focuses" +
            migration.substringAfter("create table voice_tutor_lesson_focuses").substringBefore(") engine=") + ")")
            .replace(Regex("(?is)\\s+comment\\s+'(?:[^']|'')*'"), "")
        execute(ddl)
        seed("legacy-live", 11, 11)
        seed("legacy-deleted", null, 12)
        seed("legacy-unknown", null, null)
    }

    @Test
    fun `backfill retains known historical IDs including deleted nodes and leaves unknown anchors unselected`(): Unit = runBlocking {
        execute(backfill)
        val rows = database.sql("select session_id, revision, study_id, captured_at from voice_tutor_lesson_focuses order by session_id")
            .map { row, _ ->
                listOf(row.get("session_id", String::class.java), (row.get("revision") as Number).toLong(),
                    (row.get("study_id") as Number).toLong(), row.get("captured_at", LocalDateTime::class.java))
            }.all().collectList().awaitSingle()

        assertThat(rows).containsExactly(listOf("legacy-deleted", 0L, 12L, at), listOf("legacy-live", 0L, 11L, at))
        seed("new-discovery", null, null)
        assertThat(count()).isEqualTo(2)
        assertThat(migration.lowercase()).doesNotContain("update voice_tutor_sessions", "delete from", "questions", "user_voice_quota")
    }

    @Test
    fun `focus history cascades only with its session and rejects duplicate epochs or synthetic study IDs`(): Unit = runBlocking {
        execute(backfill)
        for (sql in listOf(
            "insert into voice_tutor_lesson_focuses values ('legacy-live', 0, 99, current_timestamp)",
            "insert into voice_tutor_lesson_focuses values ('legacy-live', 1, 0, current_timestamp)",
            "insert into voice_tutor_lesson_focuses values ('legacy-live', -1, 11, current_timestamp)",
        )) {
            assertThat(runCatching { execute(sql) }.isFailure).isTrue()
        }
        execute("delete from voice_tutor_sessions where id = 'legacy-live'")
        assertThat(count()).isEqualTo(1)
        val remaining = database.sql("select study_id from voice_tutor_lesson_focuses")
            .map { row, _ -> (row.get("study_id") as Number).toLong() }.one().awaitSingle()
        assertThat(remaining).isEqualTo(12)
    }

    private suspend fun seed(id: String, liveId: Long?, acceptedId: Long?) {
        var query = database.sql("insert into voice_tutor_sessions(id, study_id, accepted_study_id, created_at) values (:id, :live, :accepted, :at)")
            .bind("id", id).bind("at", at)
        query = liveId?.let { query.bind("live", it) } ?: query.bindNull("live", Long::class.javaObjectType)
        query = acceptedId?.let { query.bind("accepted", it) } ?: query.bindNull("accepted", Long::class.javaObjectType)
        query.fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun count(): Long = database.sql("select count(*) as n from voice_tutor_lesson_focuses")
        .map { row, _ -> (row.get("n") as Number).toLong() }.one().awaitSingle()

    private suspend fun execute(sql: String) {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
    }
}
