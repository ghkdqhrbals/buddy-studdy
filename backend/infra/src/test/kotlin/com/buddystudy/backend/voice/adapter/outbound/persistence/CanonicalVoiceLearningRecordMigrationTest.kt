package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
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

/**
 * Exercises V105's actual core INSERT-SELECT in H2 with only its two JSON scalar functions aliased.
 * This is not a claim that H2 validates MySQL CHECK/DDL, JSON_TABLE, transactional DDL or locking.
 * The full migration still needs an isolated MySQL verification before a single-version cutover.
 */
@Timeout(15)
class CanonicalVoiceLearningRecordMigrationTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///canonical-voice-migration-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val migration = Files.readString(migrationPath())
    private val coreInsert = "insert into questions (" + migration.substringAfter("insert into questions (").substringBefore(';')

    @BeforeEach
    fun schema(): Unit = runBlocking {
        for (sql in listOf(
            "create alias json_extract for \"com.buddystudy.backend.voice.adapter.outbound.persistence.VoiceRecordMigrationJsonFunctions.extract\"",
            "create alias json_unquote for \"com.buddystudy.backend.voice.adapter.outbound.persistence.VoiceRecordMigrationJsonFunctions.unquote\"",
            "create table studies(id bigint primary key, user_id bigint not null)",
            "create table voice_tutor_sessions(id varchar(36) primary key)",
            """
            create table voice_study_learning_records (
                id bigint primary key, user_id bigint not null, session_id varchar(36) not null,
                study_id bigint not null, parent_study_id bigint, kind varchar(24) not null,
                question clob not null, answer clob, score int, feedback clob, topic varchar(255) not null,
                difficulty int not null, source_language varchar(8) not null,
                source_languages_json clob not null, source_hash varchar(64) not null,
                occurred_at timestamp(6) not null, created_at timestamp(6) not null,
                foreign key (session_id) references voice_tutor_sessions(id) on delete cascade
            )
            """,
            """
            create table voice_study_learning_localizations (
                record_id bigint not null, target_language varchar(8) not null,
                fields_json clob not null, request_token varchar(36) not null, source_hash varchar(64) not null,
                primary key(record_id, target_language),
                foreign key(record_id) references voice_study_learning_records(id) on delete cascade
            )
            """,
            """
            create table questions (
                id bigint auto_increment primary key, device_id varchar(191) not null,
                user_id bigint, study_id bigint, record_type varchar(24) not null default 'QUESTION',
                voice_record_id bigint unique, question clob not null, hint clob, topic varchar(255) not null,
                difficulty_level int not null, scheduled_for timestamp(6) not null, status varchar(32) not null,
                source varchar(64) not null, answer clob, score int, is_correct boolean, feedback clob, explanation clob,
                source_language varchar(16) not null, answer_source_language varchar(16), ai_response_source_language varchar(16),
                is_public boolean not null, created_at timestamp(6) not null, updated_at timestamp(6) not null,
                foreign key(study_id) references studies(id) on delete set null,
                foreign key(voice_record_id) references voice_study_learning_records(id) on delete cascade
            )
            """,
        )) execute(sql.trimIndent())
        execute("insert into studies(id, user_id) values (10, 7), (20, 99)")
        execute("insert into voice_tutor_sessions(id) values ('synthetic-call')")
    }

    @Test
    fun `auto-increment backfill maps colliding legacy IDs without mutating ordinary records or republishing old voice`(): Unit = runBlocking {
        execute("""
            insert into questions(id, device_id, user_id, study_id, question, topic, difficulty_level,
                scheduled_for, status, source, source_language, is_public, created_at, updated_at)
            values (5, 'synthetic-device', 7, 10, 'Ordinary draft question', 'Ordinary', 2,
                current_timestamp, 'ungraded', 'manual', 'ko', true, current_timestamp, current_timestamp)
        """.trimIndent())
        seedVoice(5, 10)
        seedVoice(6, 999) // Saved node was removed, but its voice history must not disappear.
        execute("""
            insert into voice_study_learning_localizations(record_id, target_language, fields_json, request_token, source_hash)
            values (5, 'en', '{"question":"Existing translation"}', 'stable-legacy-token', 'source-hash-5')
        """.trimIndent())

        execute(coreInsert)
        val copied = copiedRows()

        assertThat(copied).hasSize(2)
        assertThat(copied.map { it.voiceId }).containsExactly(5L, 6L)
        assertThat(copied.map { it.id }.distinct()).hasSize(2)
        assertThat(copied.map { it.id }).doesNotContain(5L)
        assertThat(copied.map { it.isPublic }).containsOnly(false)
        assertThat(copied.map { it.studyId }).containsExactly(10L, null)
        assertThat(copied.map { it.status }).containsOnly("completed")
        assertThat(copied.map { it.source }).containsOnly("voice_tutor")
        assertThat(copied.map { it.deviceId }).containsOnly("")
        assertThat(copied.map { it.question }).containsExactly("원문 질문 5", "원문 질문 6")
        assertThat(copied.map { it.answer }).containsExactly("Original learner answer 5", "Original learner answer 6")
        assertThat(copied.map { it.feedback }).containsExactly("元のフィードバック 5", "元のフィードバック 6")
        assertThat(copied.map { it.questionLanguage }).containsOnly("ko")
        assertThat(copied.map { it.answerLanguage }).containsOnly("en")
        assertThat(copied.map { it.feedbackLanguage }).containsOnly("ja")
        assertThat(copied.map { it.score }).containsOnly(85)
        assertThat(copied.map { it.at }).containsOnly(AT)
        assertThat(text("select question from questions where id = 5")).isEqualTo("Ordinary draft question")
        assertThat(text("select status from questions where id = 5")).isEqualTo("ungraded")
        assertThat(text("select request_token from voice_study_learning_localizations")).isEqualTo("stable-legacy-token")
        assertThat(text("select source_hash from voice_study_learning_records where id = 5")).isEqualTo("source-hash-5")

        execute(coreInsert)
        assertThat(copiedRows()).isEqualTo(copied)
    }

    @Test
    fun `foreign live node never becomes an owned association and original scoreless evidence stays scoreless`(): Unit = runBlocking {
        seedVoice(1, 20)
        execute("update voice_study_learning_records set answer = null, score = null, feedback = null")

        execute(coreInsert)
        val copied = copiedRows().single()

        assertThat(copied.studyId).isNull()
        assertThat(copied.answer).isNull()
        assertThat(copied.score).isNull()
        assertThat(copied.feedback).isNull()
        assertThat(copied.answerLanguage).isNull()
        assertThat(copied.feedbackLanguage).isNull()
        assertThat(text("select cast(study_id as varchar) from voice_study_learning_records")).isEqualTo("20")
    }

    @Test
    fun `migration orders source copy and search before removal and keeps privacy cutoff without touching quota or transcripts`() {
        val lower = migration.lowercase()
        assertThat(lower.indexOf("insert into questions (")).isLessThan(lower.indexOf("insert into question_search ("))
        assertThat(lower.indexOf("insert into question_search (")).isLessThan(lower.indexOf("drop column question"))
        assertThat(lower.indexOf("records_default_public boolean not null default false"))
            .isLessThan(lower.indexOf("alter column records_default_public set default true"))
        assertThat(coreInsert).contains("false, v.occurred_at, v.created_at")
        assertThat(coreInsert).contains("where not exists (select 1 from questions q where q.voice_record_id = v.id)")
        assertThat(coreInsert).doesNotContain("max(", "learning_records_projected_at", "allow_public_questions", "users")
        assertThat(lower).doesNotContain("update user_quota", "insert into user_quota", "update user_voice_quota",
            "delete from voice_tutor_transcript_turns", "update voice_tutor_transcript_turns", "drop table voice_tutor_results")
        assertThat(lower).contains("references voice_study_learning_records(id) on delete cascade")
        val shape = lower.substringAfter("add constraint chk_questions_voice_shape check (").substringBefore("add index")
        assertThat(shape).doesNotContain("voice_record_id")
    }

    @Test
    fun `creation snapshot migration preserves every old call and only changes the default for future calls`(): Unit = runBlocking {
        // The production COMMENT contains a semicolon. The H2 R2DBC driver itself splits that
        // quoted literal, so omit only this metadata for the H2 default/backfill behavior check.
        // The complete COMMENT and DDL are covered by the separate exact-MySQL migration check.
        val first = migration.indexOf("alter table voice_tutor_sessions")
        val second = migration.indexOf("alter table voice_tutor_sessions", first + 1)
        val end = migration.indexOf("alter table questions", second)
        val privacySteps = listOf(migration.substring(first, second), migration.substring(second, end))
            .map { it.replace(Regex("(?s)\\s+comment\\s+'(?:[^']|'')*'"), "").trim().removeSuffix(";") }
        for (sql in privacySteps) execute(sql)
        execute("insert into voice_tutor_sessions(id) values ('new-call')")
        val snapshots = database.sql("select id, records_default_public from voice_tutor_sessions order by id")
            .map { row, _ -> row.get("id", String::class.java)!! to row.get("records_default_public", Boolean::class.javaObjectType)!! }
            .all().collectList().awaitSingle().toMap()

        assertThat(snapshots).containsEntry("synthetic-call", false).containsEntry("new-call", true)
    }

    private suspend fun seedVoice(id: Long, studyId: Long) {
        database.sql("""
            insert into voice_study_learning_records(id, user_id, session_id, study_id, kind, question, answer, score, feedback,
                topic, difficulty, source_language, source_languages_json, source_hash, occurred_at, created_at)
            values(:id, 7, 'synthetic-call', :studyId, 'TUTOR_QUESTION', :question, :answer, 85, :feedback,
                'Frozen topic', 4, 'ko', '{"question":"ko","answer":"en","feedback":"ja"}', :hash, :at, :created)
        """.trimIndent()).bind("id", id).bind("studyId", studyId)
            .bind("question", "원문 질문 $id").bind("answer", "Original learner answer $id").bind("feedback", "元のフィードバック $id")
            .bind("hash", "source-hash-$id").bind("at", AT).bind("created", AT.plusMinutes(3))
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun copiedRows(): List<CopiedRow> = database.sql("select * from questions where record_type = 'VOICE_TUTOR' order by voice_record_id")
        .map { row, _ ->
            CopiedRow(
                (row.get("id") as Number).toLong(), (row.get("voice_record_id") as Number).toLong(),
                (row.get("study_id") as? Number)?.toLong(), row.get("is_public", Boolean::class.javaObjectType)!!,
                row.get("status", String::class.java)!!, row.get("source", String::class.java)!!, row.get("device_id", String::class.java)!!,
                row.get("question", String::class.java)!!, row.get("answer", String::class.java), row.get("feedback", String::class.java),
                row.get("source_language", String::class.java)!!, row.get("answer_source_language", String::class.java), row.get("ai_response_source_language", String::class.java),
                (row.get("score") as? Number)?.toInt(), row.get("created_at", LocalDateTime::class.java)!!,
            )
        }.all().collectList().awaitSingle()

    private suspend fun text(sql: String) = database.sql(sql).map { row, _ -> row.get(0, String::class.java)!! }.one().awaitSingle()
    private suspend fun execute(sql: String) { database.sql(sql).fetch().rowsUpdated().awaitSingle() }

    private data class CopiedRow(
        val id: Long, val voiceId: Long, val studyId: Long?, val isPublic: Boolean,
        val status: String, val source: String, val deviceId: String,
        val question: String, val answer: String?, val feedback: String?,
        val questionLanguage: String, val answerLanguage: String?, val feedbackLanguage: String?,
        val score: Int?, val at: LocalDateTime,
    )

    private companion object {
        val AT: LocalDateTime = LocalDateTime.parse("2026-08-31T03:04:05.123456")
        fun migrationPath(): Path = listOf(
            Path.of("../tutor/src/main/resources/db/migration-mysql/V105__canonical_voice_learning_records.sql"),
            Path.of("tutor/src/main/resources/db/migration-mysql/V105__canonical_voice_learning_records.sql"),
            Path.of("backend/tutor/src/main/resources/db/migration-mysql/V105__canonical_voice_learning_records.sql"),
        ).first { Files.isRegularFile(it) }
    }
}

/** Only the scalar JSON operations used by the production backfill, not a general MySQL emulation. */
object VoiceRecordMigrationJsonFunctions {
    @JvmStatic
    fun extract(json: String?, path: String?): String? =
        if (json == null || path == null) null else JsonMapperProvider.mapper.readTree(json)[path.removePrefix("$.")]?.toString()

    @JvmStatic
    fun unquote(json: String?): String? = json?.let {
        val value = JsonMapperProvider.mapper.readTree(it)
        if (value.isTextual) value.textValue() else it
    }
}
