package com.buddystudy.backend.study.adapter.outbound.persistence

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
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.MySqlDialect
import org.springframework.data.relational.core.mapping.RelationalMappingContext
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.time.OffsetDateTime

class QuestionRepositoryLikedPageTest {
    private val connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///liked-public-questions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    )
    private val database = DatabaseClient.create(connectionFactory)
    private val repository: QuestionRepository

    init {
        val conversions = R2dbcCustomConversions.of(
            MySqlDialect.INSTANCE,
            OffsetDateTimeToInstant,
            StringToSupportedLanguage,
            StringToQuestionStatus,
            StringToQuestionSource,
        )
        val mappingContext = RelationalMappingContext().also {
            it.setSimpleTypeHolder(conversions.simpleTypeHolder)
        }
        val converter = MappingR2dbcConverter(mappingContext, conversions)
        val template = R2dbcEntityTemplate(database, MySqlDialect.INSTANCE, converter)
        repository = QuestionRepository(template, QuestionSearchProjectionManager(database))
    }

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        listOf("question_search", "user_blocks", "question_likes", "questions", "users").forEach {
            execute("drop table if exists $it")
        }
        execute(
            """
            create table users (
                id bigint primary key,
                display_name varchar(255) not null,
                allow_public_questions boolean not null
            )
            """.trimIndent(),
        )
        execute(
            """
            create table questions (
                id bigint primary key,
                device_id varchar(191) not null,
                user_id bigint,
                study_id bigint,
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
                is_public boolean not null,
                created_at timestamp with time zone not null,
                updated_at timestamp with time zone not null
            )
            """.trimIndent(),
        )
        execute(
            """
            create table question_likes (
                id bigint primary key,
                question_id bigint not null,
                user_id bigint not null,
                created_at timestamp with time zone not null,
                unique (question_id, user_id)
            )
            """.trimIndent(),
        )
        execute(
            """
            create table user_blocks (
                id bigint primary key,
                blocker_user_id bigint not null,
                blocked_user_id bigint not null,
                created_at timestamp with time zone not null
            )
            """.trimIndent(),
        )
        execute(
            """
            create table question_search (
                question_id bigint not null,
                language varchar(16) not null,
                topic text,
                question text,
                answer text,
                feedback text,
                explanation text,
                primary key (question_id, language)
            )
            """.trimIndent(),
        )
        execute("insert into users values (10, 'Visible Author', true), (11, 'Hidden Author', false), (12, 'Blocked Author', true)")
        insertQuestion(101, 10, "Newest needle", "graded", "Answer", publicQuestion = true)
        insertQuestion(102, 10, "Middle", "graded", "Answer", publicQuestion = true)
        insertQuestion(103, 10, "Oldest", "graded", "Answer", publicQuestion = true)
        insertQuestion(104, 10, "Private", "graded", "Answer", publicQuestion = false)
        insertQuestion(105, 10, "Deleted", "graded", "Answer", publicQuestion = true, deleted = true)
        insertQuestion(106, 11, "Hidden", "graded", "Answer", publicQuestion = true)
        insertQuestion(107, 12, "Blocked", "graded", "Answer", publicQuestion = true)
        insertQuestion(108, 10, "Ungraded", "ungraded", "Answer", publicQuestion = true)
        insertQuestion(109, 10, "Blank", "graded", "  ", publicQuestion = true)
        insertQuestion(110, 10, "Other user like", "graded", "Answer", publicQuestion = true)
        (101L..110L).forEach { id ->
            execute(
                """
                insert into question_search (
                    question_id, language, topic, question, answer, feedback, explanation
                ) values ($id, 'ko', '${if (id == 101L) "Newest needle" else "Topic $id"}', 'Question $id', 'Answer', 'Good', 'Because')
                """.trimIndent(),
            )
        }
        listOf(
            101L to 30,
            102L to 20,
            103L to 10,
            104L to 40,
            105L to 50,
            106L to 60,
            107L to 70,
            108L to 80,
            109L to 90,
        ).forEachIndexed { index, (questionId, seconds) ->
            execute(
                "insert into question_likes values (${index + 1}, $questionId, 7, timestamp with time zone '2026-06-10 00:00:${seconds.coerceAtMost(59)}+00:00')",
            )
        }
        execute("insert into question_likes values (20, 110, 8, timestamp with time zone '2026-06-10 00:02:00+00:00')")
        execute("insert into user_blocks values (1, 7, 12, timestamp with time zone '2026-06-10 00:00:00+00:00')")
    }

    @Test
    fun `liked page filters visibility before exact offset and count`(): Unit = runBlocking {
        val page = repository.findLikedPublicAnsweredVisibleTo(
            viewerUserId = 7,
            query = null,
            language = "ko",
            limit = 20,
            offset = 2,
        )

        assertThat(page.totalElements).isEqualTo(3)
        assertThat(page.pageable.offset).isEqualTo(2)
        assertThat(page.content.map { it.id }).containsExactly(103L)
    }

    @Test
    fun `liked page searches the requested language projection`(): Unit = runBlocking {
        val page = repository.findLikedPublicAnsweredVisibleTo(
            viewerUserId = 7,
            query = " needle ",
            language = "KO",
            limit = 20,
            offset = 0,
        )

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content.map { it.id }).containsExactly(101L)
    }

    private suspend fun insertQuestion(
        id: Long,
        userId: Long,
        topic: String,
        status: String,
        answer: String,
        publicQuestion: Boolean,
        deleted: Boolean = false,
    ) {
        val deletedAt = if (deleted) "timestamp with time zone '2026-06-11 00:00:00+00:00'" else "null"
        execute(
            """
            insert into questions (
                id, device_id, user_id, question, topic, source_language, difficulty_level,
                scheduled_for, status, answer, answer_source_language, score, is_correct,
                feedback, explanation, ai_response_source_language, answered_at, graded_at,
                deleted_at, source, is_public, created_at, updated_at
            ) values (
                $id, 'device-$userId', $userId, 'Question $id', '$topic', 'ko', 5,
                timestamp with time zone '2026-06-10 00:00:00+00:00', '$status', '$answer', 'ko', 90, true,
                'Good', 'Because', 'ko', timestamp with time zone '2026-06-10 00:01:00+00:00',
                timestamp with time zone '2026-06-10 00:02:00+00:00', $deletedAt, 'manual', $publicQuestion,
                timestamp with time zone '2026-06-10 00:00:00+00:00', timestamp with time zone '2026-06-10 00:02:00+00:00'
            )
            """.trimIndent(),
        )
    }

    private suspend fun execute(sql: String) {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
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
