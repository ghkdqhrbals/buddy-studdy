package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.stats.adapter.outbound.persistence.StudyGrowthStatsRepository
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.domain.PageRequest
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.MySqlDialect
import org.springframework.data.relational.core.mapping.RelationalMappingContext
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.time.OffsetDateTime

class QuestionThreadPersistenceTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///question-followups;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val conversions = R2dbcCustomConversions.of(MySqlDialect.INSTANCE,
        OffsetDateTimeToInstant, StringToSupportedLanguage, StringToQuestionStatus, StringToQuestionSource, QuestionSourceToString)
    private val context = RelationalMappingContext().also { it.setSimpleTypeHolder(conversions.simpleTypeHolder) }
    private val template = R2dbcEntityTemplate(database, MySqlDialect.INSTANCE, MappingR2dbcConverter(context, conversions))
    private val repository = QuestionRepository(template, QuestionSearchProjectionManager(database))
    private val growth = StudyGrowthStatsRepository(template)

    @BeforeEach
    fun setup(): Unit = runBlocking {
        execute("drop table if exists questions")
        execute("""
            create table questions (
                id bigint primary key,
                device_id varchar(191) not null,
                user_id bigint,
                study_id bigint,
                parent_record_id bigint,
                root_record_id bigint,
                follow_up_depth integer not null default 0,
                concept_id bigint,
                concept_key varchar(255),
                angle_key varchar(255),
                question text not null,
                hint text,
                topic varchar(255) not null,
                source_language varchar(16) not null,
                difficulty_level integer not null,
                scheduled_for timestamp with time zone not null,
                sent_at timestamp with time zone,
                status varchar(32) not null,
                error text,
                answer text,
                answer_source_language varchar(16),
                score integer,
                is_correct boolean,
                feedback text,
                explanation text,
                ai_response_source_language varchar(16),
                grading_rubric_json text,
                grading_assessment_json text,
                grading_verdict varchar(32),
                grading_confidence double,
                grading_policy_version varchar(64),
                grading_model varchar(128),
                grading_request_id varchar(36),
                grading_status varchar(40),
                grading_error varchar(255),
                grading_last_event_id bigint,
                grading_requested_at timestamp with time zone,
                grading_started_at timestamp with time zone,
                answered_at timestamp with time zone,
                graded_at timestamp with time zone,
                skipped_at timestamp with time zone,
                deleted_at timestamp with time zone,
                source varchar(64) not null,
                record_type varchar(24) not null default 'QUESTION',
                voice_record_id bigint,
                is_public boolean not null,
                created_at timestamp with time zone not null,
                updated_at timestamp with time zone not null
            )

        """.trimIndent())
        execute("create unique index uq_followup_parent on questions(parent_record_id)")
        execute("create unique index uq_followup_depth on questions(root_record_id, follow_up_depth)")
        insert(1, 7, "manual", 0, "null", "null", 40)
        insert(2, 7, "follow_up", 1, "1", "1", 90)
        insert(3, 7, "follow_up", 2, "2", "1", 100)
        insert(4, 8, "manual", 0, "null", "null", 75)
        insert(5, 7, "custom_question", 0, "null", "null", null)
    }

    @Test
    fun `thread is owner-scoped ordered bounded and keeps deleted children for limit enforcement`(): Unit = runBlocking {
        execute("update questions set deleted_at = current_timestamp where id=2")
        val records = repository.findThreadByRootAndUser(1, 7)
        assertThat(records.map { it.id }).containsExactly(1, 2, 3)
        assertThat(records.map { it.followUpDepth }).containsExactly(0, 1, 2)
        assertThat(records[1].deletedAt).isNotNull()
        assertThat(records[2].parentRecordId).isEqualTo(2)
        assertThat(records[2].rootRecordId).isEqualTo(1)
        assertThat(repository.findThreadByRootAndUser(1, 8)).isEmpty()
    }

    @Test
    fun `ability inputs exclude both coached followups and custom records`(): Unit = runBlocking {
        assertThat(repository.findAllGradedForStats(PageRequest.of(0, 20)).content.map { it.id })
            .containsExactlyInAnyOrder(1, 4)
        assertThat(repository.findLatestGradedByUserAndTopics(7, listOf("Redis"), 20).map { it.id })
            .containsExactly(1)
        val records = growth.findByUser(7, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))
        assertThat(records).hasSize(1)
        assertThat(records.single().score).isEqualTo(40)
    }

    @Test
    fun `database rejects a second child for the same parent even after soft deletion`(): Unit = runBlocking {
        execute("update questions set deleted_at = current_timestamp where id=2")
        val failure = runCatching { insert(6, 7, "follow_up", 1, "1", "1", 50) }.exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(repository.findThreadByRootAndUser(1, 7)).hasSize(3)
    }

    private suspend fun insert(id: Long, owner: Long, source: String, depth: Int, parent: String, root: String, score: Int?) {
        execute("""
            insert into questions(id, device_id, user_id, study_id, parent_record_id, root_record_id, follow_up_depth,
                question, answer, topic, source_language, difficulty_level, scheduled_for, status, score,
                source, is_public, answered_at, created_at, updated_at)
            values($id, 'device', $owner, 11, $parent, $root, $depth, 'Question', 'Answer', 'Redis', 'en', 5,
                timestamp with time zone '2026-09-24 00:00:00+00:00', 'graded', ${score ?: "null"}, '$source', false,
                timestamp with time zone '2026-09-24 00:01:00+00:00', timestamp with time zone '2026-09-24 00:00:00+00:00',
                timestamp with time zone '2026-09-24 00:01:00+00:00')
        """.trimIndent())
    }

    private suspend fun execute(sql: String) { database.sql(sql).fetch().rowsUpdated().awaitSingle() }
    @WritingConverter
    private object QuestionSourceToString : Converter<QuestionSource, String> {
        override fun convert(source: QuestionSource): String = source.databaseValue
    }

    @ReadingConverter
    private object OffsetDateTimeToInstant : Converter<OffsetDateTime, Instant> {
        override fun convert(source: OffsetDateTime): Instant = source.toInstant()
    }

    @ReadingConverter
    private object StringToSupportedLanguage : Converter<String, SupportedLanguage> {
        override fun convert(source: String): SupportedLanguage = SupportedLanguage.fromDatabaseValue(source)
    }

    @ReadingConverter
    private object StringToQuestionStatus : Converter<String, QuestionStatus> {
        override fun convert(source: String): QuestionStatus = QuestionStatus.fromDatabaseValue(source)
    }

    @ReadingConverter
    private object StringToQuestionSource : Converter<String, QuestionSource> {
        override fun convert(source: String): QuestionSource = QuestionSource.fromDatabaseValue(source)
    }
}
