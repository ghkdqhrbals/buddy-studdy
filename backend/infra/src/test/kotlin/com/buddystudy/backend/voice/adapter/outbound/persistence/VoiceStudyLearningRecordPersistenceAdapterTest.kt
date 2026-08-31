package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.port.ContentLanguageDetectionPort
import com.buddystudy.backend.localization.application.port.ContentTranslationEventPort
import com.buddystudy.backend.voice.adapter.outbound.VoiceTutorExplorationJsonCodec
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionSearchProjectionManager
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.voice.domain.VoiceStudyLearningRecord
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.Row
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Independent H2/MySQL-mode contracts, not an execution of MySQL Flyway DDL or its locking engine.
 * JSON uses CLOB; relevant V97/V102/V103/V104/V105 unique/check/cascade constraints are mirrored explicitly.
 * The fake outbox writes real SQL through the same transaction context. No containers, sockets,
 * real accounts, model calls, question-generation/grading flows or quota tables participate.
 */
@Timeout(20)
class VoiceStudyLearningRecordPersistenceAdapterTest {
    private val fixture = VoiceStudyLearningRecordTestFixture
    private val now = fixture.now
    private val connections = ConnectionFactories.get(
        "r2dbc:h2:mem:///voice-study-record-${UUID.randomUUID()};" +
            "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0;LOCK_TIMEOUT=5000",
    )
    private var schemaConnection: Connection? = null
    private val database = DatabaseClient.create(connections)
    private val transactions = TransactionalOperator.create(R2dbcTransactionManager(connections))
    private val outbox = SqlOutbox(database)
    private var detectLanguage: (String, String) -> String = { _, _ -> "ko" }
    private val languageDetector = object : ContentLanguageDetectionPort {
        override fun detect(text: String, fallbackLanguage: String): String = detectLanguage(text, fallbackLanguage)
    }
    private val searchProjection = QuestionSearchProjectionManager(database)
    private val questions = SqlQuestions(database, searchProjection)
    private val adapter = VoiceStudyLearningRecordPersistenceAdapter(database, outbox, languageDetector, questions, searchProjection)

    @BeforeEach
    fun schema(): Unit = runBlocking {
        // H2 2.4 CHECK(IN(...)) captures the DDL SessionLocal in its constant-set comparator.
        // Keep that physical connection alive; retaining only the in-memory DB is insufficient.
        // It is never shared by the production adapter's independent transaction connections.
        schemaConnection = connections.create().awaitSingle()
        executeSchema("create table users (id bigint primary key, status varchar(24) not null)")
        executeSchema("""
            create table studies (
                id bigint primary key, user_id bigint not null, parent_study_id bigint,
                topic varchar(255) not null, difficulty_level int not null,
                foreign key (user_id) references users(id) on delete cascade
            )
        """.trimIndent())
        executeSchema("""
            create table voice_tutor_sessions (
                id varchar(36) primary key, user_id bigint not null, study_id bigint, accepted_study_id bigint,
                language varchar(8) not null, ended_at timestamp(6), result_status varchar(24) not null,
                records_default_public boolean not null default true,
                updated_at timestamp(6) not null,
                foreign key (user_id) references users(id) on delete cascade,
                foreign key (study_id) references studies(id) on delete set null,
                check (accepted_study_id is null or accepted_study_id > 0)
            )
        """.trimIndent())
        executeSchema("""
            create table voice_tutor_lesson_focuses (
                session_id varchar(36) not null, revision bigint not null,
                study_id bigint not null, captured_at timestamp(6) not null,
                primary key (session_id, revision),
                foreign key (session_id) references voice_tutor_sessions(id) on delete cascade,
                check (revision >= 0), check (study_id > 0)
            )
        """.trimIndent())
        executeSchema("""
            create table voice_tutor_results (
                session_id varchar(36) primary key, status varchar(24) not null,
                summary_markdown clob, explorations_json clob, learning_records_projected_at timestamp(6),
                updated_at timestamp(6) not null,
                foreign key (session_id) references voice_tutor_sessions(id) on delete cascade,
                check (status in ('PROCESSING', 'COMPLETED', 'FAILED'))
            )
        """.trimIndent())
        executeSchema("""
            create table voice_tutor_study_snapshots (
                session_id varchar(36) not null, study_id bigint not null, parent_study_id bigint,
                topic varchar(255) not null, difficulty int not null, captured_at timestamp(6) not null,
                primary key (session_id, study_id),
                foreign key (session_id) references voice_tutor_sessions(id) on delete cascade,
                check (study_id > 0), check (difficulty between 1 and 10)
            )
        """.trimIndent())
        executeSchema("""
            create table voice_tutor_study_revisions (
                session_id varchar(36) not null, revision bigint not null,
                study_id bigint not null, parent_study_id bigint,
                topic varchar(255) not null, difficulty int not null, captured_at timestamp(6) not null,
                primary key (session_id, revision),
                foreign key (session_id) references voice_tutor_sessions(id) on delete cascade,
                check (study_id > 0), check (revision > 0), check (difficulty between 1 and 10)
            )
        """.trimIndent())
        executeSchema("""
            create table voice_tutor_transcript_turns (
                id bigint primary key, session_id varchar(36) not null, provider_item_id varchar(191) not null,
                role varchar(16) not null, transcript clob not null, sequence_number bigint not null,
                occurred_at timestamp(6) not null, lesson_revision bigint not null default 0,
                unique (session_id, provider_item_id, role), unique (session_id, sequence_number),
                foreign key (session_id) references voice_tutor_sessions(id) on delete cascade,
                check (role in ('USER', 'TUTOR')), check (lesson_revision >= -1)
            )
        """.trimIndent())
        executeSchema("""
            create table voice_study_learning_records (
                id bigint auto_increment primary key, user_id bigint not null, session_id varchar(36) not null,
                study_id bigint not null, parent_study_id bigint, kind varchar(24) not null,
                strengths_json clob not null, improvements_json clob not null,
                depth_summary clob not null, question_turn_id bigint not null,
                answer_turn_ids_json clob not null, feedback_turn_ids_json clob not null,
                source_languages_json clob not null, source_hash varchar(64) not null, created_at timestamp(6) not null,
                unique (session_id, question_turn_id),
                foreign key (session_id) references voice_tutor_sessions(id) on delete cascade,
                check (kind in ('TUTOR_QUESTION', 'LEARNER_QUESTION'))
            )
        """.trimIndent())
        executeSchema("create index idx_voice_study_record_frozen_node on voice_study_learning_records(user_id, study_id, id)")
        executeSchema("""
            create table questions (
                id bigint auto_increment primary key, user_id bigint not null, study_id bigint,
                device_id varchar(191) not null, record_type varchar(24) not null default 'QUESTION', voice_record_id bigint unique,
                topic varchar(255) not null, difficulty_level int not null, question clob not null, answer clob, score int, feedback clob,
                source_language varchar(16) not null, answer_source_language varchar(16), ai_response_source_language varchar(16),
                status varchar(24) not null, source varchar(24) not null, is_public boolean not null,
                scheduled_for timestamp(6) not null, created_at timestamp(6) not null, updated_at timestamp(6) not null,
                deleted_at timestamp(6), skipped_at timestamp(6),
                foreign key (voice_record_id) references voice_study_learning_records(id) on delete cascade,
                foreign key (study_id) references studies(id) on delete set null,
                check (record_type in ('QUESTION', 'VOICE_TUTOR')),
                check (record_type <> 'VOICE_TUTOR' or (status = 'completed' and source = 'voice_tutor' and device_id = '')),
                check (difficulty_level between 1 and 10),
                check (score is null or (score between 0 and 100 and answer is not null))
            )
        """.trimIndent())
        executeSchema("""
            create table question_search (
                question_id bigint not null, language varchar(16) not null,
                topic clob, question clob, answer clob, feedback clob, explanation clob,
                updated_at timestamp(6) not null, primary key (question_id, language),
                foreign key (question_id) references questions(id) on delete cascade
            )
        """.trimIndent())
        executeSchema("""
            create table voice_study_learning_localizations (
                record_id bigint not null, target_language varchar(8) not null, source_language varchar(8) not null,
                source_hash varchar(64) not null, request_token varchar(36) not null, status varchar(16) not null,
                fields_json clob, provider varchar(64), error_message varchar(255),
                created_at timestamp(6) not null, updated_at timestamp(6) not null,
                primary key (record_id, target_language),
                foreign key (record_id) references voice_study_learning_records(id) on delete cascade,
                check (target_language in ('ko', 'en', 'ja')), check (status in ('PENDING', 'READY', 'FAILED'))
            )
        """.trimIndent())
        executeSchema("""
            create table synthetic_translation_outbox (
                id bigint auto_increment primary key, event_id varchar(100) not null unique,
                content_type varchar(32) not null, content_id bigint not null, target_language varchar(8) not null,
                source_hash varchar(64) not null, requested_at timestamp(6) not null, created_at timestamp(6) not null
            )
        """.trimIndent())
    }

    @AfterEach
    fun closeSchemaConnection(): Unit = runBlocking {
        val connection = schemaConnection
        schemaConnection = null
        if (connection != null) {
            withTimeout(5_000) { connection.close().awaitFirstOrNull() }
        }
    }

    @Test
    fun `completion persists exact source text frozen level and UTC time while translations remain separate pending rows`(): Unit = runBlocking {
        seed()
        execute("update studies set topic = 'Renamed live topic', difficulty_level = 9 where id = 11")
        append()
        val record = onlyRecord()
        val source = fixture.turns()
        assertThat(record.question).isEqualTo(source[0].transcript)
        assertThat(record.answer).isEqualTo(source[1].transcript + "\n" + source[2].transcript)
        assertThat(record.feedback).isEqualTo(source[3].transcript)
        assertThat(record.score).isEqualTo(85)
        assertThat(record.topic).isEqualTo(fixture.TOPIC)
        assertThat(record.difficulty).isEqualTo(3)
        assertThat(record.studyId).isEqualTo(11)
        assertThat(record.parentStudyId).isEqualTo(10)
        assertThat(record.createdAt).isEqualTo(source[0].occurredAt)
        assertThat(rawInstant("select created_at from voice_study_learning_records")).isEqualTo(now)
        assertThat(record.sourceLanguage).isEqualTo("ko")
        assertThat(record.sourceLanguages.keys).isEqualTo(record.translatableFields().keys)
        assertThat(projected()).isTrue()
        assertThat(outbox.events().map { it.targetLanguage }).containsExactly("en", "ja")
        assertThat(outbox.events().map { it.contentType }).containsOnly(LocalizableContentType.VOICE_STUDY_RECORD)
        assertThat(outbox.events().map { it.sourceHash }).containsOnly(record.sourceHash)
        assertThat(adapter.snapshot(record.id, "ko")).isNull()
        assertThat(adapter.snapshot(record.id, "en")?.status).isEqualTo("PENDING")
        assertThat(adapter.snapshot(record.id, "en")?.fields).isEmpty()
        assertThat(count("voice_tutor_transcript_turns")).isEqualTo(6)
        assertThat(record.recordId).isPositive()
        assertThat(text("select record_type from questions")).isEqualTo("VOICE_TUTOR")
        assertThat(text("select status from questions")).isEqualTo("completed")
        assertThat(text("select source from questions")).isEqualTo("voice_tutor")
        assertThat(text("select device_id from questions")).isEmpty()
        assertThat(text("select question from questions")).isEqualTo(record.question)
        assertThat(count("questions")).isEqualTo(1)
        assertThat(count("question_search")).isEqualTo(3)
        assertThat(database.sql("select is_public from questions").map { row, _ -> row.get("is_public", Boolean::class.javaObjectType)!! }.one().awaitSingle()).isTrue()
    }

    @Test
    fun `canonical allocation cannot overwrite an ordinary record with the same legacy voice ID`(): Unit = runBlocking {
        seed()
        execute("""
            insert into questions(id, user_id, study_id, device_id, record_type, topic, difficulty_level, question,
                source_language, status, source, is_public, scheduled_for, created_at, updated_at)
            values (1, 7, 11, 'synthetic-question-device', 'QUESTION', 'Ordinary saved topic', 4,
                'Ordinary immutable question', 'ko', 'ungraded', 'manual', false, current_timestamp, current_timestamp, current_timestamp)
        """.trimIndent())

        append()
        val voice = onlyRecord()

        assertThat(voice.id).isEqualTo(1)
        assertThat(voice.recordId).isNotEqualTo(voice.id)
        assertThat(text("select question from questions where id = 1")).isEqualTo("Ordinary immutable question")
        assertThat(text("select status from questions where id = 1")).isEqualTo("ungraded")
        assertThat(count("questions")).isEqualTo(2)
        assertThat(questions.saved).hasSize(1)
        assertThat(questions.saved.single().voiceRecordId).isEqualTo(voice.id)
        assertThat(outbox.events().map { it.contentId }).containsOnly(voice.id)
    }

    @Test
    fun `a pre-migration private call remains private when its previously missing records are recovered later`(): Unit = runBlocking {
        seed()
        execute("update voice_tutor_sessions set records_default_public = false")
        val candidate = adapter.completedCandidates(1).single()
        transaction { adapter.appendCompletedSession(candidate.userId, candidate.sessionId, candidate.explorations, now.plusSeconds(86_400)) }

        assertThat(database.sql("select is_public from questions").map { row, _ -> row.get("is_public", Boolean::class.javaObjectType)!! }.one().awaitSingle()).isFalse()
        assertThat(onlyRecord().question).isEqualTo(fixture.turns().first().transcript)
        assertThat(projected()).isTrue()
    }

    @Test
    fun `per-field source languages are carried into canonical columns without conflating learner answer and tutor feedback`(): Unit = runBlocking {
        seed()
        val answer = fixture.turns()[1].transcript + "\n" + fixture.turns()[2].transcript
        val feedback = fixture.turns()[3].transcript
        detectLanguage = { text, _ -> when (text) { answer -> "en"; feedback -> "ja"; else -> "ko" } }

        append()

        assertThat(text("select source_language from questions")).isEqualTo("ko")
        assertThat(text("select answer_source_language from questions")).isEqualTo("en")
        assertThat(text("select ai_response_source_language from questions")).isEqualTo("ja")
        assertThat(onlyRecord().answer).isEqualTo(answer)
        assertThat(onlyRecord().feedback).isEqualTo(feedback)
    }

    @Test
    fun `canonical soft deletion hides legacy detail translations and recovery without deleting original session evidence`(): Unit = runBlocking {
        seed()
        append()
        val record = onlyRecord()
        val event = outbox.events().first { it.targetLanguage == "en" }
        execute("update questions set deleted_at = updated_at where voice_record_id = ${record.id}")

        assertThat(adapter.findOwned(7, record.id)).isNull()
        assertThat(adapter.findAllOwned(7, listOf(record.id))).isEmpty()
        assertThat(adapter.content(record.id)).isNull()
        assertThat(adapter.snapshot(record.id, "en")).isNull()
        transaction { adapter.request(record, "en", now.plusSeconds(600)) }
        assertThat(transaction { adapter.saveReady(record, event, translation(record), now.plusSeconds(601)) }).isFalse()
        adapter.markFailed(event, "not retained", now.plusSeconds(602))
        assertThat(text("select status from voice_study_learning_localizations where target_language = 'en'")).isEqualTo("PENDING")
        clearProjectionMarker()
        append(at = now.plusSeconds(603))

        assertThat(count("questions")).isEqualTo(1)
        assertThat(count("voice_study_learning_records")).isEqualTo(1)
        assertThat(count("voice_tutor_transcript_turns")).isEqualTo(6)
        assertThat(questions.saved).hasSize(1)
        assertThat(projected()).isTrue()
        assertThat(outbox.events()).hasSize(2)
        assertThat(text("select question from questions")).isEqualTo(record.question)
    }

    @Test
    fun `an inconsistent canonical owner cannot leak a legacy voice record or accept a late translation`(): Unit = runBlocking {
        seed()
        append()
        val record = onlyRecord()
        val event = outbox.events().first { it.targetLanguage == "en" }
        execute("update questions set user_id = 99")

        assertThat(adapter.findOwned(7, record.id)).isNull()
        assertThat(adapter.findOwned(99, record.id)).isNull()
        assertThat(adapter.content(record.id)).isNull()
        assertThat(adapter.snapshot(record.id, "en")).isNull()
        assertThat(transaction { adapter.saveReady(record, event, translation(record), now.plusSeconds(1)) }).isFalse()
        assertThat(count("voice_tutor_transcript_turns")).isEqualTo(6)
    }

    @Test
    fun `repeated completion and replay after a missing projection marker never overwrite originals or duplicate translation work`(): Unit = runBlocking {
        seed()
        append()
        val original = onlyRecord()
        val changed = fixture.exploration().copy(exchanges = listOf(fixture.exchange().copy(question = "Rewritten", answer = "Rewritten", score = 10)))
        append(listOf(changed), now.plusSeconds(1))
        assertThat(onlyRecord()).isEqualTo(original)
        assertThat(outbox.events()).hasSize(2)

        clearProjectionMarker()
        append(listOf(fixture.exploration()), now.plusSeconds(2))
        assertThat(onlyRecord()).isEqualTo(original)
        assertThat(count("voice_study_learning_records")).isEqualTo(1)
        assertThat(count("questions")).isEqualTo(1)
        assertThat(outbox.events()).hasSize(2)
        assertThat(projected()).isTrue()
    }

    @Test
    fun `foreign inactive unended and noncompleted sessions cannot project or enqueue any content`(): Unit = runBlocking {
        seed()
        transaction { adapter.appendCompletedSession(99, fixture.SESSION_ID, listOf(fixture.exploration()), now) }
        execute("update users set status = 'WITHDRAWN' where id = 7")
        append()
        execute("update users set status = 'ACTIVE' where id = 7")
        execute("update voice_tutor_results set status = 'PROCESSING'")
        append()
        execute("update voice_tutor_results set status = 'COMPLETED'")
        execute("update voice_tutor_sessions set ended_at = null")
        append()
        assertThat(count("voice_study_learning_records")).isZero()
        assertThat(count("voice_study_learning_localizations")).isZero()
        assertThat(outbox.events()).isEmpty()
        assertThat(projected()).isFalse()
    }

    @Test
    fun `deleted foreign owned or other root targets cannot acquire an anchored record even when names match`(): Unit = runBlocking {
        seed()
        execute("delete from studies where id = 11")
        append()
        assertThat(count("voice_study_learning_records")).isZero()
        assertThat(projected()).isTrue()
        clearProjectionMarker()
        execute("insert into users(id, status) values (99, 'ACTIVE')")
        seedStudy(fixture.snapshots().last(), userId = 99)
        append()
        assertThat(count("voice_study_learning_records")).isZero()

        clearProjectionMarker()
        val outside = listOf(VoiceTutorStudySnapshot(90, null, "Other root", 3), VoiceTutorStudySnapshot(91, 90, fixture.TOPIC, 3))
        for (snapshot in outside) {
            seedStudy(snapshot)
            seedSnapshot(snapshot)
        }
        append(listOf(fixture.exploration().copy(studyId = 91)))
        assertThat(count("voice_study_learning_records")).isZero()
        assertThat(outbox.events()).isEmpty()
        assertThat(count("voice_tutor_transcript_turns")).isEqualTo(6)
    }

    @Test
    fun `one question ambiguously assigned to two owned nodes is skipped without losing an independent learner question`(): Unit = runBlocking {
        seed()
        val sibling = VoiceTutorStudySnapshot(12, 10, "Queues", 4)
        seedStudy(sibling)
        seedSnapshot(sibling)
        append(listOf(
            fixture.exploration(), fixture.exploration().copy(studyId = 12, topic = "Queues"),
            fixture.exploration().copy(exchanges = listOf(fixture.learnerQuestion())),
        ))
        val record = onlyRecord()
        assertThat(record.questionTurnId).isEqualTo(5)
        assertThat(record.studyId).isEqualTo(11)
        assertThat(record.score).isNull()
        assertThat(record.question).isEqualTo(fixture.turns()[4].transcript)
        assertThat(record.answer).isEqualTo(fixture.turns()[5].transcript)
    }

    @Test
    fun `owned queries cannot read another owner and translation content disappears for an inactive owner`(): Unit = runBlocking {
        seed()
        append()
        val record = onlyRecord()
        assertThat(adapter.findOwned(99, record.id)).isNull()
        assertThat(adapter.findAllOwned(99, listOf(record.id))).isEmpty()
        assertThat(adapter.findAllOwned(7, emptyList())).isEmpty()
        assertThat(adapter.findAllOwned(7, listOf(record.id))).containsExactly(record)
        assertThat(runCatching { adapter.findAllOwned(7, (1L..101L).toList()) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(adapter.content(record.id)).isEqualTo(record)
        execute("update users set status = 'WITHDRAWN' where id = 7")
        assertThat(adapter.content(record.id)).isNull()
    }

    @Test
    fun `a successful localization updates only its matching pending token and never overwrites source fields or score`(): Unit = runBlocking {
        seed()
        append()
        val record = onlyRecord()
        val event = outbox.events().single { it.targetLanguage == "en" }
        val translated = translation(record)
        assertThat(adapter.saveReady(record, event, translated, now.plusSeconds(2))).isTrue()
        assertThat(adapter.snapshot(record.id, "en")?.status).isEqualTo("READY")
        assertThat(adapter.snapshot(record.id, "en")?.fields).isEqualTo(translated.fields)
        assertThat(adapter.snapshot(record.id, "en")?.provider).isEqualTo("synthetic-translator")
        assertThat(text("select question from question_search where language = 'en'"))
            .isEqualTo(translated.fields["question"])
        assertThat(text("select question from question_search where language = 'ko'"))
            .isEqualTo(record.question)
        assertThat(onlyRecord()).isEqualTo(record)
        assertThat(adapter.saveReady(record, event, translated, now.plusSeconds(3))).isFalse()
        adapter.markFailed(event, "private provider detail", now.plusSeconds(4))
        assertThat(adapter.snapshot(record.id, "en")?.status).isEqualTo("READY")
        transaction { adapter.request(record, "en-US", now.plusSeconds(3_600)) }
        assertThat(outbox.events()).hasSize(2)
    }

    @Test
    fun `ready translation and canonical search projection roll back together if the derived search update fails`(): Unit = runBlocking {
        seed()
        append()
        val record = onlyRecord()
        val event = outbox.events().single { it.targetLanguage == "en" }
        executeSchema("alter table question_search add constraint synthetic_search_rejection check (question not like 'Translated:%')")

        val failure = runCatching {
            transaction { adapter.saveReady(record, event, translation(record), now.plusSeconds(2)) }
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(adapter.snapshot(record.id, "en")?.status).isEqualTo("PENDING")
        assertThat(adapter.snapshot(record.id, "en")?.fields).isEmpty()
        assertThat(count("question_search")).isEqualTo(3)
        assertThat(text("select question from question_search where language = 'en'")).isEqualTo(record.question)
        assertThat(onlyRecord()).isEqualTo(record)
        assertThat(outbox.events()).hasSize(2)

        executeSchema("alter table question_search drop constraint synthetic_search_rejection")
        assertThat(transaction { adapter.saveReady(record, event, translation(record), now.plusSeconds(3)) }).isTrue()
        assertThat(adapter.snapshot(record.id, "en")?.status).isEqualTo("READY")
        assertThat(onlyRecord()).isEqualTo(record)
    }

    @Test
    fun `wrong content type id hash token and translated field shape cannot replace a localization`(): Unit = runBlocking {
        seed()
        append()
        val record = onlyRecord()
        val event = outbox.events().single { it.targetLanguage == "en" }
        for (wrong in listOf(
            event.copy(contentType = LocalizableContentType.QUESTION),
            event.copy(contentId = record.id + 1),
            event.copy(sourceHash = "b".repeat(64)),
            event.copy(eventId = "content-translation-wrong-token"),
        )) {
            assertThat(adapter.saveReady(record, wrong, translation(record), now.plusSeconds(1))).isFalse()
            adapter.markFailed(wrong, "private detail", now.plusSeconds(1))
        }
        for (fields in listOf(
            translation(record).fields - "question",
            translation(record).fields + ("score" to "100"),
            translation(record).fields + ("question" to " "),
            translation(record).fields + ("question" to "x".repeat(512 * 1024)),
        )) {
            val failure = runCatching { adapter.saveReady(record, event, ContentTranslationResult(fields, "synthetic"), now.plusSeconds(1)) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(adapter.snapshot(record.id, "en")?.status).isEqualTo("PENDING")
        assertThat(onlyRecord()).isEqualTo(record)
    }

    @Test
    fun `failed translation is sanitized and requeues at the delay boundary with a new token that rejects late results`(): Unit = runBlocking {
        seed()
        append()
        val record = onlyRecord()
        val original = outbox.events().single { it.targetLanguage == "en" }
        adapter.markFailed(original, "api-key=private-error-details", now.plusSeconds(1))
        assertThat(adapter.snapshot(record.id, "en")?.status).isEqualTo("FAILED")
        assertThat(text("select error_message from voice_study_learning_localizations where target_language = 'en'"))
            .isEqualTo("Voice learning record translation failed.")
        transaction { adapter.request(record, "en", now.plusSeconds(300)) }
        assertThat(outbox.events()).hasSize(2)
        transaction { adapter.request(record, "en", now.plusSeconds(301)) }
        val current = outbox.events().last { it.targetLanguage == "en" }
        assertThat(current.eventId).isNotEqualTo(original.eventId)
        assertThat(outbox.events()).hasSize(3)
        assertThat(adapter.snapshot(record.id, "en")?.status).isEqualTo("PENDING")
        assertThat(adapter.saveReady(record, original, translation(record), now.plusSeconds(302))).isFalse()
        adapter.markFailed(original, "stale failure", now.plusSeconds(302))
        assertThat(adapter.snapshot(record.id, "en")?.status).isEqualTo("PENDING")
        assertThat(adapter.saveReady(record, current, translation(record), now.plusSeconds(303))).isTrue()
        assertThat(onlyRecord()).isEqualTo(record)
    }

    @Test
    fun `an expired pending request is requeued once and compare-and-set also checks the current canonical source hash`(): Unit = runBlocking {
        seed()
        append()
        val record = onlyRecord()
        val original = outbox.events().single { it.targetLanguage == "en" }
        transaction { adapter.request(record, "en", now.plusSeconds(300)) }
        val renewed = outbox.events().last { it.targetLanguage == "en" }
        assertThat(renewed.eventId).isNotEqualTo(original.eventId)
        transaction { adapter.request(record, "en", now.plusSeconds(300)) }
        assertThat(outbox.events()).hasSize(3)
        assertThat(adapter.saveReady(record, original, translation(record), now.plusSeconds(301))).isFalse()
        // Simulate a future source revision, without rewriting any original text.
        database.sql("update voice_study_learning_records set source_hash = :hash where id = :id")
            .bind("hash", "b".repeat(64)).bind("id", record.id).fetch().rowsUpdated().awaitSingle()
        assertThat(adapter.saveReady(record, renewed, translation(record), now.plusSeconds(301))).isFalse()
        val revised = onlyRecord()
        transaction { adapter.request(revised, "en", now.plusSeconds(301)) }
        val current = outbox.events().last { it.targetLanguage == "en" }
        assertThat(current.sourceHash).isEqualTo(revised.sourceHash)
        assertThat(current.eventId).isNotEqualTo(renewed.eventId)
        assertThat(adapter.saveReady(revised, current, translation(revised), now.plusSeconds(302))).isTrue()
    }

    @Test
    fun `summary completion record localization and outbox writes roll back together when enqueueing fails`(): Unit = runBlocking {
        seed(resultStatus = "PROCESSING")
        outbox.failAfterInsert = true
        val failure = runCatching {
            transaction {
                // Equivalent preceding completion writes, not a claim that H2 executes MySQL UPDATE JOIN.
                completeSummaryFixture()
                adapter.appendCompletedSession(7, fixture.SESSION_ID, listOf(fixture.exploration()), now)
            }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(text("select status from voice_tutor_results")).isEqualTo("PROCESSING")
        assertThat(text("select summary_markdown from voice_tutor_results")).isEqualTo("Original canonical summary")
        assertThat(text("select result_status from voice_tutor_sessions")).isEqualTo("PROCESSING")
        assertThat(projected()).isFalse()
        assertThat(count("voice_study_learning_records")).isZero()
        assertThat(count("questions")).isZero()
        assertThat(count("question_search")).isZero()
        assertThat(count("voice_study_learning_localizations")).isZero()
        assertThat(outbox.events()).isEmpty()
        assertThat(count("voice_tutor_transcript_turns")).isEqualTo(6)

        outbox.failAfterInsert = false
        transaction {
            completeSummaryFixture()
            adapter.appendCompletedSession(7, fixture.SESSION_ID, listOf(fixture.exploration()), now)
        }
        assertThat(text("select status from voice_tutor_results")).isEqualTo("COMPLETED")
        assertThat(projected()).isTrue()
        assertThat(count("voice_study_learning_records")).isEqualTo(1)
        assertThat(count("questions")).isEqualTo(1)
        assertThat(outbox.events()).hasSize(2)
    }

    @Test
    fun `retry token replacement and outbox insertion roll back together if the new enqueue fails`(): Unit = runBlocking {
        seed()
        append()
        val record = onlyRecord()
        val event = outbox.events().single { it.targetLanguage == "en" }
        adapter.markFailed(event, "synthetic failure", now.plusSeconds(1))
        val before = text("select request_token from voice_study_learning_localizations where target_language = 'en'")
        outbox.failAfterInsert = true
        assertThat(runCatching {
            transaction { adapter.request(record, "en", now.plusSeconds(301)) }
        }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(text("select request_token from voice_study_learning_localizations where target_language = 'en'")).isEqualTo(before)
        assertThat(adapter.snapshot(record.id, "en")?.status).isEqualTo("FAILED")
        assertThat(outbox.events()).hasSize(2)
        assertThat(onlyRecord()).isEqualTo(record)
    }

    @Test
    fun `concurrent completion waits on the session lock and emits each record and localization job once`(): Unit = runBlocking {
        seed()
        val firstEnqueued = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        outbox.afterAppend = { attempt ->
            if (attempt == 1) {
                firstEnqueued.complete(Unit)
                releaseFirst.await()
            }
        }
        val first = async(Dispatchers.Default) { append() }
        try {
            withTimeout(3_000) { firstEnqueued.await() }
            val started = CompletableDeferred<Unit>()
            val second = async(Dispatchers.Default) {
                started.complete(Unit)
                append()
            }
            withTimeout(3_000) { started.await() }
            assertThat(withTimeoutOrNull(100) { second.await(); true }).isNull()
            releaseFirst.complete(Unit)
            withTimeout(5_000) { first.await(); second.await() }
            assertThat(count("voice_study_learning_records")).isEqualTo(1)
            assertThat(count("voice_study_learning_localizations")).isEqualTo(2)
            assertThat(outbox.events()).hasSize(2)
            assertThat(projected()).isTrue()
        } finally {
            releaseFirst.complete(Unit)
        }
    }

    @Test
    fun `candidate recovery includes only unprojected completed ended calls of active owners and bounds its page`(): Unit = runBlocking {
        seed()
        for (index in 0..11) seedHeader("candidate-${index.toString().padStart(2, '0')}")
        seedHeader("not-ended", ended = false)
        seedHeader("still-processing", resultStatus = "PROCESSING")
        seedHeader("failed-result", resultStatus = "FAILED")
        seedHeader("already-projected")
        execute("update voice_tutor_results set learning_records_projected_at = updated_at where session_id = 'already-projected'")
        execute("insert into users(id, status) values (99, 'WITHDRAWN')")
        seedHeader("inactive-owner", userId = 99)
        val candidates = adapter.completedCandidates(100)
        assertThat(candidates).hasSize(10)
        assertThat(candidates.map { it.sessionId }).containsExactlyElementsOf((0..9).map { "candidate-${it.toString().padStart(2, '0')}" })
        assertThat(candidates.map { it.userId }).containsOnly(7L)
        assertThat(candidates.first().explorations).containsExactly(fixture.exploration())
        assertThat(adapter.completedCandidates(0)).hasSize(1)
    }

    @Test
    fun `session cascades remove derived records and translations while a prior node deletion preserves canonical evidence`(): Unit = runBlocking {
        seed()
        append()
        val record = onlyRecord()
        execute("delete from studies where id = 11")
        assertThat(count("voice_tutor_transcript_turns")).isEqualTo(6)
        assertThat(adapter.findOwned(7, record.id)).isEqualTo(record)
        execute("delete from voice_tutor_sessions where id = '${fixture.SESSION_ID}'")
        assertThat(count("voice_study_learning_records")).isZero()
        assertThat(count("questions")).isZero()
        assertThat(count("question_search")).isZero()
        assertThat(count("voice_study_learning_localizations")).isZero()
        assertThat(count("voice_tutor_transcript_turns")).isZero()
        assertThat(count("voice_tutor_results")).isZero()
        assertThat(count("voice_tutor_study_snapshots")).isZero()
        assertThat(adapter.content(record.id)).isNull()
        // Outbox records are durable historical requests, not foreign-key children or live audio.
        assertThat(outbox.events()).hasSize(2)
    }

    @Test
    fun `managed completion and localization request entry points declare transactional boundaries`() {
        for (name in listOf("appendCompletedSession", "request", "saveReady")) {
            val method = VoiceStudyLearningRecordPersistenceAdapter::class.java.methods.single { it.name == name }
            assertThat(method.getAnnotation(Transactional::class.java)).describedAs(name).isNotNull()
        }
    }

    @Test
    fun `versioned transcript persistence projects old pending answer and new question at their own captured levels`(): Unit = runBlocking {
        seed()
        val revised = fixture.snapshots().last().copy(topic = "Renamed eviction", difficulty = 8, revision = 1)
        seedRevision(revised)
        execute("update studies set topic = 'Renamed eviction', difficulty_level = 8 where id = 11")
        // The first tutor question was created at epoch zero; every subsequent event is newer.
        execute("update voice_tutor_transcript_turns set lesson_revision = 1 where id >= 2")
        val merged = fixture.exploration().copy(topic = revised.topic, exchanges = listOf(fixture.exchange(), fixture.learnerQuestion()))
        append(listOf(merged))
        val ids = database.sql("select id from voice_study_learning_records order by question_turn_id")
            .map { row, _ -> (row.get("id") as Number).toLong() }.all().collectList().awaitSingle()
        val records = adapter.findAllOwned(7, ids).sortedBy { it.questionTurnId }
        assertThat(records).hasSize(2)
        assertThat(records.map { it.questionTurnId }).containsExactly(1, 5)
        assertThat(records.map { it.topic }).containsExactly(fixture.TOPIC, revised.topic)
        assertThat(records.map { it.difficulty }).containsExactly(3, 8)
        assertThat(records.first().question).isEqualTo(fixture.turns()[0].transcript)
        assertThat(records.first().answer).isEqualTo(fixture.turns()[1].transcript + "\n" + fixture.turns()[2].transcript)
        assertThat(records.first().score).isEqualTo(85)
        assertThat(records.last().score).isNull()
        assertThat(outbox.events()).hasSize(4)
        append(listOf(merged))
        assertThat(adapter.findAllOwned(7, ids).sortedBy { it.questionTurnId }).isEqualTo(records)
        assertThat(count("voice_tutor_study_snapshots")).isEqualTo(3)
        assertThat(count("voice_tutor_study_revisions")).isEqualTo(1)
        assertThat(outbox.events()).hasSize(4)
    }

    @Test
    fun `deleting selected study nulls only its live FK and preserves root anchoring for a surviving sibling`(): Unit = runBlocking {
        seed()
        val sibling = VoiceTutorStudySnapshot(12, 1, "Queues", 4)
        seedStudy(sibling)
        seedSnapshot(sibling)
        execute("delete from studies where id in (10, 11)")
        val anchor = database.sql("select study_id, accepted_study_id from voice_tutor_sessions")
            .map { row, _ -> (row.get("study_id") as? Number)?.toLong() to (row.get("accepted_study_id") as? Number)?.toLong() }
            .one().awaitSingle()
        assertThat(anchor.first).isNull()
        assertThat(anchor.second).isEqualTo(10)
        append(listOf(fixture.exploration().copy(studyId = 12, topic = sibling.topic)))
        assertThat(onlyRecord().studyId).isEqualTo(12)
        assertThat(count("voice_tutor_transcript_turns")).isEqualTo(6)
        assertThat(count("voice_tutor_study_snapshots")).isEqualTo(4)
    }

    @Test
    fun `unknown and future persisted question epochs leave private source in session without enqueueing node translations`(): Unit = runBlocking {
        seed()
        for (revision in listOf(-1L, 1L)) {
            execute("update voice_tutor_transcript_turns set lesson_revision = $revision where id = 1")
            clearProjectionMarker()
            append()
            assertThat(count("voice_study_learning_records")).isZero()
            assertThat(outbox.events()).isEmpty()
            assertThat(projected()).isTrue()
            assertThat(count("voice_tutor_transcript_turns")).isEqualTo(6)
        }
    }

    @Test
    fun `a legacy missing accepted anchor is not reconstructed from mutable or matching live metadata`(): Unit = runBlocking {
        seed()
        execute("update voice_tutor_sessions set accepted_study_id = null")
        append()
        assertThat(count("voice_study_learning_records")).isZero()
        assertThat(count("voice_tutor_transcript_turns")).isEqualTo(6)
        assertThat(projected()).isTrue()
        assertThat(outbox.events()).isEmpty()
    }

    @Test
    fun `discovery session without a selected lesson preserves summary and transcript without creating common records`(): Unit = runBlocking {
        seed()
        execute("update voice_tutor_sessions set study_id = null, accepted_study_id = null")

        append()

        assertThat(count("questions")).isZero()
        assertThat(count("voice_study_learning_records")).isZero()
        assertThat(count("voice_tutor_transcript_turns")).isEqualTo(6)
        assertThat(projected()).isTrue()
        assertThat(outbox.events()).isEmpty()
    }

    @Test
    fun `selected discovery lesson creates one canonical record with the original source and is replay safe`(): Unit = runBlocking {
        seed()
        execute("update voice_tutor_sessions set study_id = 11, accepted_study_id = null")
        seedRevision(fixture.snapshots().last().copy(revision = 1))
        seedFocus(11, 1)
        execute("update voice_tutor_transcript_turns set lesson_revision = 1")

        append()
        val record = onlyRecord()
        assertThat(record.studyId).isEqualTo(11)
        assertThat(record.recordId).isNotNull()
        assertThat(record.question).isEqualTo(fixture.turns().first().transcript)
        assertThat(record.score).isEqualTo(85)
        assertThat(count("questions")).isEqualTo(1)
        assertThat(questions.saved.single().studyId).isEqualTo(11)
        assertThat(questions.saved.single().id).isPositive().isEqualTo(record.recordId)
        assertThat(outbox.events()).hasSize(2)

        clearProjectionMarker()
        append()
        assertThat(onlyRecord()).isEqualTo(record)
        assertThat(count("questions")).isEqualTo(1)
        assertThat(outbox.events()).hasSize(2)
    }

    @Test
    fun `common records keep per-question focus across two trees instead of using the session final study pointer`(): Unit = runBlocking {
        seed()
        val other = VoiceTutorStudySnapshot(90, null, "Message ordering", 7)
        seedStudy(other)
        seedSnapshot(other)
        seedRevision(fixture.snapshots().last().copy(revision = 1))
        seedRevision(other.copy(revision = 2))
        seedFocus(11, 1)
        seedFocus(90, 2)
        execute("update voice_tutor_sessions set study_id = 90, accepted_study_id = null")
        execute("update voice_tutor_transcript_turns set lesson_revision = case when id = 1 then 1 else 2 end")
        val second = fixture.exploration().copy(topic = other.topic, studyId = 90, exchanges = listOf(fixture.learnerQuestion()))

        append(listOf(fixture.exploration(), second))

        val ids = database.sql("select id from voice_study_learning_records order by question_turn_id")
            .map { row, _ -> (row.get("id") as Number).toLong() }.all().collectList().awaitSingle()
        val records = adapter.findAllOwned(7, ids).sortedBy { it.questionTurnId }
        assertThat(records.map { it.studyId }).containsExactly(11, 90)
        assertThat(records.map { it.difficulty }).containsExactly(3, 7)
        assertThat(records.map { it.recordId }.distinct()).hasSize(2).doesNotContainNull()
        assertThat(records.first().answer).isEqualTo(fixture.turns()[1].transcript + "\n" + fixture.turns()[2].transcript)
        assertThat(records.first().score).isEqualTo(85)
        assertThat(records.last().score).isNull()
        assertThat(questions.saved.map { it.studyId }).containsExactly(11, 90)
        assertThat(count("questions")).isEqualTo(2)
        assertThat(count("voice_tutor_transcript_turns")).isEqualTo(6)
        assertThat(outbox.events()).hasSize(4)
    }

    private suspend fun seedFocus(studyId: Long, revision: Long) {
        database.sql("insert into voice_tutor_lesson_focuses(session_id, revision, study_id, captured_at) values (:sessionId, :revision, :studyId, :now)")
            .bind("sessionId", fixture.SESSION_ID).bind("revision", revision).bind("studyId", studyId).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun seed(resultStatus: String = "COMPLETED") {
        execute("insert into users(id, status) values (7, 'ACTIVE')")
        for (snapshot in fixture.snapshots()) seedStudy(snapshot)
        seedHeader(fixture.SESSION_ID, resultStatus)
        for (snapshot in fixture.snapshots()) seedSnapshot(snapshot)
        for (turn in fixture.turns()) {
            database.sql("""
                insert into voice_tutor_transcript_turns(id, session_id, provider_item_id, role, transcript, sequence_number, occurred_at, lesson_revision)
                values (:id, :sessionId, :provider, :role, :text, :sequence, :occurred, :revision)
            """.trimIndent()).bind("id", turn.id).bind("sessionId", fixture.SESSION_ID).bind("provider", turn.providerItemId)
                .bind("role", turn.role.name).bind("text", turn.transcript).bind("sequence", turn.sequenceNumber)
                .bind("occurred", turn.occurredAt.utc()).bind("revision", turn.lessonRevision).fetch().rowsUpdated().awaitSingle()
        }
    }

    private suspend fun seedHeader(
        sessionId: String,
        resultStatus: String = "COMPLETED",
        ended: Boolean = true,
        userId: Long = 7,
    ) {
        var sessionInsert = database.sql("""
            insert into voice_tutor_sessions(id, user_id, study_id, accepted_study_id, language, ended_at, result_status, updated_at)
            values (:id, :userId, 10, 10, 'ko', :ended, :status, :now)
        """.trimIndent()).bind("id", sessionId).bind("userId", userId).bind("status", resultStatus).bind("now", now.utc())
        sessionInsert = if (ended) sessionInsert.bind("ended", now.utc()) else sessionInsert.bindNull("ended", LocalDateTime::class.java)
        sessionInsert.fetch().rowsUpdated().awaitSingle()
        database.sql("""
            insert into voice_tutor_results(session_id, status, summary_markdown, explorations_json, updated_at)
            values (:id, :status, 'Original canonical summary', :explorations, :now)
        """.trimIndent()).bind("id", sessionId).bind("status", resultStatus)
            .bind("explorations", VoiceTutorExplorationJsonCodec.encode(listOf(fixture.exploration())))
            .bind("now", now.utc()).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun seedStudy(snapshot: VoiceTutorStudySnapshot, userId: Long = 7) {
        var query = database.sql("""
            insert into studies(id, user_id, parent_study_id, topic, difficulty_level)
            values (:id, :userId, :parent, :topic, :difficulty)
        """.trimIndent()).bind("id", snapshot.studyId).bind("userId", userId)
            .bind("topic", snapshot.topic).bind("difficulty", snapshot.difficulty)
        query = snapshot.parentStudyId?.let { query.bind("parent", it) } ?: query.bindNull("parent", Long::class.javaObjectType)
        query.fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun seedSnapshot(snapshot: VoiceTutorStudySnapshot) {
        var query = database.sql("""
            insert into voice_tutor_study_snapshots(session_id, study_id, parent_study_id, topic, difficulty, captured_at)
            values (:sessionId, :id, :parent, :topic, :difficulty, :now)
        """.trimIndent()).bind("sessionId", fixture.SESSION_ID).bind("id", snapshot.studyId)
            .bind("topic", snapshot.topic).bind("difficulty", snapshot.difficulty).bind("now", now.minusSeconds(60).utc())
        query = snapshot.parentStudyId?.let { query.bind("parent", it) } ?: query.bindNull("parent", Long::class.javaObjectType)
        query.fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun seedRevision(snapshot: VoiceTutorStudySnapshot) {
        var query = database.sql("""
            insert into voice_tutor_study_revisions(session_id, revision, study_id, parent_study_id, topic, difficulty, captured_at)
            values (:sessionId, :revision, :id, :parent, :topic, :difficulty, :now)
        """.trimIndent()).bind("sessionId", fixture.SESSION_ID).bind("revision", snapshot.revision).bind("id", snapshot.studyId)
            .bind("topic", snapshot.topic).bind("difficulty", snapshot.difficulty).bind("now", now.minusSeconds(10).utc())
        query = snapshot.parentStudyId?.let { query.bind("parent", it) } ?: query.bindNull("parent", Long::class.javaObjectType)
        query.fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun append(explorations: List<VoiceTutorExploration> = listOf(fixture.exploration()), at: Instant = now) {
        transaction { adapter.appendCompletedSession(7, fixture.SESSION_ID, explorations, at) }
    }

    private suspend fun clearProjectionMarker() = execute("update voice_tutor_results set learning_records_projected_at = null")

    private suspend fun completeSummaryFixture() {
        execute("update voice_tutor_results set status = 'COMPLETED', summary_markdown = 'Completed canonical summary'")
        execute("update voice_tutor_sessions set result_status = 'COMPLETED'")
    }

    private fun translation(record: VoiceStudyLearningRecord) = ContentTranslationResult(
        fields = record.translatableFields().mapValues { (_, value) -> "Translated: $value" },
        provider = "synthetic-translator",
    )

    private suspend fun onlyRecord(): VoiceStudyLearningRecord {
        val ids = database.sql("select id from voice_study_learning_records order by id")
            .map { row, _ -> (row.get("id") as Number).toLong() }.all().collectList().awaitSingle()
        assertThat(ids).hasSize(1)
        return requireNotNull(adapter.findOwned(7, ids.single()))
    }

    private suspend fun projected(): Boolean = database.sql(
        "select learning_records_projected_at from voice_tutor_results where session_id = :id",
    ).bind("id", fixture.SESSION_ID).map { row, _ -> row.get("learning_records_projected_at") != null }.one().awaitSingle()

    private suspend fun count(table: String): Long = database.sql("select count(*) as n from $table")
        .map { row, _ -> (row.get("n") as Number).toLong() }.one().awaitSingle()

    private suspend fun text(sql: String): String = database.sql(sql)
        .map { row, _ -> requireNotNull(row.get(0, String::class.java)) }.one().awaitSingle()

    private suspend fun rawInstant(sql: String): Instant = database.sql(sql)
        .map { row, _ -> requireNotNull(row.get(0, LocalDateTime::class.java)).toInstant(ZoneOffset.UTC) }.one().awaitSingle()

    private suspend fun execute(sql: String) { database.sql(sql).fetch().rowsUpdated().awaitSingle() }

    private suspend fun executeSchema(sql: String) {
        requireNotNull(schemaConnection).createStatement(sql).execute().awaitSingle().rowsUpdated.awaitSingle()
    }

    private suspend fun <T : Any> transaction(block: suspend () -> T): T =
        requireNotNull(transactions.executeAndAwait { block() })

    private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    /** The real QuestionPort save boundary is exercised with SQL, without enabling its generation use cases. */
    private class SqlQuestions(
        private val database: DatabaseClient,
        private val searchProjection: QuestionSearchProjectionManager,
    ) : QuestionPort by unsupportedPort() {
        val saved = mutableListOf<QuestionEntity>()

        override suspend fun save(entity: QuestionEntity): QuestionEntity {
            check(entity.id == 0L)
            check(entity.gradingRequestId == null && entity.gradingStatus == null && entity.correct == null)
            check(entity.conceptId == null && entity.angleKey == null)
            database.sql("""
                insert into questions(user_id, study_id, device_id, record_type, voice_record_id,
                    topic, difficulty_level, question, answer, score, feedback, source_language,
                    answer_source_language, ai_response_source_language, status, source, is_public,
                    scheduled_for, created_at, updated_at)
                values (:userId, :studyId, :deviceId, :recordType, :voiceId, :topic, :difficulty,
                    :question, :answer, :score, :feedback, :language, :answerLanguage, :feedbackLanguage,
                    :status, :source, :public, :scheduled, :created, :updated)
            """.trimIndent()).bind("userId", requireNotNull(entity.userId)).nullable("studyId", entity.studyId, Long::class.javaObjectType)
                .bind("deviceId", entity.deviceId).bind("recordType", entity.recordType.name)
                .bind("voiceId", requireNotNull(entity.voiceRecordId)).bind("topic", entity.topic).bind("difficulty", entity.difficultyLevel)
                .bind("question", entity.question).nullable("answer", entity.answer, String::class.java)
                .nullable("score", entity.score, Int::class.javaObjectType).nullable("feedback", entity.feedback, String::class.java)
                .bind("language", entity.sourceLanguage.databaseValue)
                .nullable("answerLanguage", entity.answerSourceLanguage?.databaseValue, String::class.java)
                .nullable("feedbackLanguage", entity.aiResponseSourceLanguage?.databaseValue, String::class.java)
                .bind("status", entity.status.databaseValue).bind("source", entity.source.databaseValue).bind("public", entity.publicQuestion)
                .bind("scheduled", LocalDateTime.ofInstant(entity.scheduledFor, ZoneOffset.UTC))
                .bind("created", LocalDateTime.ofInstant(entity.createdAt, ZoneOffset.UTC))
                .bind("updated", LocalDateTime.ofInstant(entity.updatedAt, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
            entity.id = database.sql("select id from questions where voice_record_id = :voiceId")
                .bind("voiceId", requireNotNull(entity.voiceRecordId)).map { row, _ -> (row.get("id") as Number).toLong() }
                .one().awaitSingle()
            searchProjection.refresh(entity.id)
            saved += entity
            return entity
        }

        private fun DatabaseClient.GenericExecuteSpec.nullable(name: String, value: Any?, type: Class<*>) =
            if (value == null) bindNull(name, type) else bind(name, value)
    }

    private companion object {
        inline fun <reified T> unsupportedPort(): T = java.lang.reflect.Proxy.newProxyInstance(
            T::class.java.classLoader, arrayOf(T::class.java),
        ) { _, method, _ -> error("Unexpected ${T::class.simpleName} call: ${method.name}") } as T
    }

    private class SqlOutbox(private val database: DatabaseClient) : ContentTranslationEventPort {
        var failAfterInsert = false
        var afterAppend: (suspend (Int) -> Unit)? = null
        private val attempts = AtomicInteger()

        override suspend fun append(event: ContentTranslationRequestedEvent, now: Instant): Long {
            database.sql("""
                insert into synthetic_translation_outbox(event_id, content_type, content_id, target_language, source_hash, requested_at, created_at)
                values (:eventId, :type, :id, :target, :hash, :requested, :created)
            """.trimIndent()).bind("eventId", event.eventId).bind("type", event.contentType.name).bind("id", event.contentId)
                .bind("target", event.targetLanguage).bind("hash", event.sourceHash)
                .bind("requested", LocalDateTime.ofInstant(event.requestedAt, ZoneOffset.UTC))
                .bind("created", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).fetch().rowsUpdated().awaitSingle()
            afterAppend?.invoke(attempts.incrementAndGet())
            if (failAfterInsert) throw IllegalStateException("synthetic transactional outbox failure")
            return database.sql("select id from synthetic_translation_outbox where event_id = :eventId")
                .bind("eventId", event.eventId).map { row, _ -> (row.get("id") as Number).toLong() }.one().awaitSingle()
        }

        suspend fun events(): List<ContentTranslationRequestedEvent> = database.sql(
            "select * from synthetic_translation_outbox order by id",
        ).map { row, _ -> event(row) }.all().collectList().awaitSingle()

        private fun event(row: Row) = ContentTranslationRequestedEvent(
            eventId = row.get("event_id", String::class.java)!!,
            contentType = LocalizableContentType.valueOf(row.get("content_type", String::class.java)!!),
            contentId = (row.get("content_id") as Number).toLong(),
            targetLanguage = row.get("target_language", String::class.java)!!,
            sourceHash = row.get("source_hash", String::class.java)!!,
            requestedAt = row.get("requested_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
        )
    }
}
