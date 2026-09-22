package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.community.application.model.PublicFeedSort
import com.buddystudy.backend.community.application.model.PublicFeedScope
import com.buddystudy.backend.community.adapter.outbound.persistence.TopicSubscriptionPersistenceAdapter
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyRecordType
import com.buddystudy.backend.stats.adapter.outbound.persistence.StudyGrowthStatsRepository
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.MySqlDialect
import org.springframework.data.relational.core.mapping.RelationalMappingContext
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class QuestionRepositoryLikedPageTest {
    private val connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///liked-public-questions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    )
    private val database = DatabaseClient.create(connectionFactory)
    private val repository: QuestionRepository
    private val entityTemplate: R2dbcEntityTemplate

    init {
        val conversions = R2dbcCustomConversions.of(
            MySqlDialect.INSTANCE,
            OffsetDateTimeToInstant,
            InstantToOffsetDateTime,
            StringToSupportedLanguage,
            StringToQuestionStatus,
            QuestionStatusToString,
            StringToQuestionSource,
            StringToStudyRecordType,
        )
        val mappingContext = RelationalMappingContext().also {
            it.setSimpleTypeHolder(conversions.simpleTypeHolder)
        }
        val converter = MappingR2dbcConverter(mappingContext, conversions)
        entityTemplate = R2dbcEntityTemplate(database, MySqlDialect.INSTANCE, converter)
        repository = QuestionRepository(entityTemplate, QuestionSearchProjectionManager(database))
    }

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        listOf("user_topic_subscriptions", "question_stats", "question_search", "question_embeddings", "user_blocks", "question_likes", "questions", "voice_study_learning_records", "users").forEach {
            execute("drop table if exists $it")
        }
        execute(
            """
            create table users (
                id bigint primary key,
                display_name varchar(255) not null,
                status varchar(24) not null default 'ACTIVE',
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
                record_type varchar(24) not null default 'QUESTION',
                voice_record_id bigint,
                is_public boolean not null,
                created_at timestamp with time zone not null,
                updated_at timestamp with time zone not null
            )
            """.trimIndent(),
        )
        execute("create table voice_study_learning_records (id bigint primary key, user_id bigint not null)")
        execute(
            """
            create table question_embeddings (
                question_id bigint primary key, user_id bigint not null, study_id bigint not null,
                topic varchar(255) not null, topic_key varchar(255) not null,
                question text not null, embedding text not null,
                created_at timestamp with time zone not null, updated_at timestamp with time zone not null
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
        execute("create table question_stats (question_id bigint primary key, view_count integer, like_count integer)")
        execute("""
            create table user_topic_subscriptions (
                user_id bigint not null, topic_key varchar(120) not null, topic varchar(120) not null,
                sort_order integer not null, primary key (user_id, topic_key),
                foreign key (user_id) references users(id) on delete cascade
            )
        """.trimIndent())
        execute("insert into users (id, display_name, allow_public_questions) values (10, 'Visible Author', true), (11, 'Hidden Author', false), (12, 'Blocked Author', true)")
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

    @Test
    fun `owner records include unscored voice exchanges without crossing user or node scope`(): Unit = runBlocking {
        insertVoice(201, studyId = 501)
        insertVoice(202, studyId = 501, answer = "")
        insertVoice(203, userId = 11, studyId = 501)
        insertVoice(204, studyId = 502)
        insertVoice(205, studyId = 501, status = "ungraded")
        val owned = repository.findVisibleByUser(10, false, PageRequest.of(0, 100))
        val node = repository.findVisibleByUserAndStudyId(10, false, 501, null, PageRequest.of(0, 100))
        val searched = repository.findVisibleByUserAndStudyId(10, false, 501, "voice", PageRequest.of(0, 100))
        val hydrated = repository.findOwnedRecordsByIds(10, listOf(201, 202, 203, 205))

        assertThat(owned.content.map { it.id }).contains(201L, 202L, 204L).doesNotContain(203L, 205L)
        assertThat(node.content.map { it.id }).containsExactly(202L, 201L)
        assertThat(searched.content.map { it.id }).containsExactly(202L, 201L)
        assertThat(node.totalElements).isEqualTo(2)
        assertThat(hydrated.map { it.id }).containsExactlyInAnyOrder(201L, 202L)
        assertThat(hydrated).allMatch { it.recordType == StudyRecordType.VOICE_TUTOR && it.score == null }
    }

    @Test
    fun `new completed voice never hides an existing pending generated question`(): Unit = runBlocking {
        insertQuestion(211, 10, "Pending", "ungraded", "", publicQuestion = true)
        execute("update questions set study_id = 501, score = null where id = 211")
        insertQuestion(210, 10, "Prior generated", "graded", "Answer", publicQuestion = true)
        execute("update questions set study_id = 501 where id = 210")
        insertVoice(212, studyId = 501, score = 85)

        assertThat(repository.findLatestStatusByStudyId(501)).isEqualTo(QuestionStatus.UNGRADED)
        assertThat(repository.findLatestStatusesByStudyIds(listOf(501))).containsEntry(501L, QuestionStatus.UNGRADED)
        assertThat(repository.countPendingForStudy(501)).isEqualTo(1)
        assertThat(repository.countPendingByStudyIds(listOf(501))).containsEntry(501L, 1L)
        assertThat(repository.findLatestPendingByStudyIds(listOf(501)).map { it.id }).containsExactly(211L)
        assertThat(repository.findPendingByStudyId(501, PageRequest.of(0, 20)).content.map { it.id }).containsExactly(211L)
        assertThat(repository.findLatestCompletedByStudyIdAndUserId(501, 10)?.id).isEqualTo(210L)
    }

    @Test
    fun `owned pending topic filter precedes counting and pagination and excludes completed or voice records`(): Unit = runBlocking {
        for (id in 271L..273L) {
            insertQuestion(id, 10, "Shared topic name", if (id == 272L) "grading" else "ungraded", "", true)
            execute("update questions set study_id = 501, score = null where id = $id")
        }
        insertQuestion(274, 11, "Shared topic name", "ungraded", "", true)
        execute("update questions set study_id = 501, score = null where id = 274")
        insertQuestion(275, 10, "Shared topic name", "ungraded", "", true)
        execute("update questions set study_id = 502, score = null where id = 275")
        insertQuestion(276, 10, "Shared topic name", "ungraded", "", true, deleted = true)
        execute("update questions set study_id = 501, score = null where id = 276")
        insertQuestion(277, 10, "Shared topic name", "ungraded", "", true)
        execute("update questions set study_id = 501, score = null, skipped_at = updated_at where id = 277")
        for ((index, status) in listOf("skipped", "graded", "failed", "completed").withIndex()) {
            val id = 278L + index
            insertQuestion(id, 10, "Shared topic name", status, "", true)
            execute("update questions set study_id = 501, score = null where id = $id")
        }
        insertVoice(282, studyId = 501, status = "ungraded")

        val first = repository.findPendingByUserAndStudyId(10, 501, PageRequest.of(0, 1))
        val second = repository.findPendingByUserAndStudyId(10, 501, PageRequest.of(1, 1))
        val last = repository.findPendingByUserAndStudyId(10, 501, PageRequest.of(2, 1))
        val exhausted = repository.findPendingByUserAndStudyId(10, 501, PageRequest.of(3, 1))
        val exactOffset = object : Pageable by PageRequest.of(0, 2) {
            override fun getOffset(): Long = 1
        }

        assertThat(first.content.map { it.id }).containsExactly(273L)
        assertThat(second.content.map { it.id }).containsExactly(272L)
        assertThat(last.content.map { it.id }).containsExactly(271L)
        assertThat(listOf(first, second, last, exhausted).map { it.totalElements }).containsOnly(3L)
        assertThat(exhausted.content).isEmpty()
        assertThat(repository.findPendingByUserAndStudyId(10, 501, exactOffset).content.map { it.id })
            .containsExactly(272L, 271L)
        assertThat(repository.findPendingByUserAndStudyId(10, 999, PageRequest.of(0, 20)).totalElements).isZero()
        // The unscoped overload also retains this owner's shared pending fixture (108).
        val allOwned = repository.findPendingByUser(10, PageRequest.of(0, 20))
        assertThat(allOwned.content.map { it.id }).containsExactly(275L, 273L, 272L, 271L, 108L)
        assertThat(allOwned.totalElements).isEqualTo(5L)
    }

    @Test
    fun `spoken scores do not become graded mastery or generation history`(): Unit = runBlocking {
        insertQuestion(221, 10, "Voice stats", "graded", "Answer", publicQuestion = true)
        execute("update questions set study_id = 501 where id = 221")
        execute("insert into question_search values (221, 'ko', 'Voice stats', 'Question 221', 'Answer', 'Good', 'Because')")
        insertVoice(222, studyId = 501, score = 99, topic = "Voice stats")
        insertVoice(223, studyId = 501, topic = "Voice stats")

        assertThat(repository.findGradedByUserAndTopics(10, listOf("Voice stats"), PageRequest.of(0, 20)).content.map { it.id })
            .containsExactly(221L)
        assertThat(repository.findGradedByUserAndQuery(10, "Voice stats", PageRequest.of(0, 20)).content.map { it.id })
            .containsExactly(221L)
        assertThat(repository.findLatestGradedByUserAndTopics(10, listOf("Voice stats"), 3).map { it.id }).containsExactly(221L)
        assertThat(repository.findAllGradedForStats(PageRequest.of(0, 100)).content.map { it.id }).doesNotContain(222L, 223L)
        assertThat(repository.findRecentQuestionTextsByStudyIdAndTopic(501, "Voice stats", PageRequest.of(0, 20)))
            .containsExactly("Question 221")
        assertThat(repository.findRecentQuestionTextsByUserIdAndTopic(10, "Voice stats", PageRequest.of(0, 20)))
            .containsExactly("Question 221")
        val growth = StudyGrowthStatsRepository(entityTemplate).findByUser(
            10, Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"),
        )
        assertThat(growth).hasSize(1)
        assertThat(growth.single().score).isEqualTo(90)
    }

    @Test
    fun `public voice eligibility is shared by detail list search and liked pagination`(): Unit = runBlocking {
        insertVoice(231)
        insertVoice(232, publicQuestion = false)
        insertVoice(233, deleted = true)
        insertVoice(234, userId = 11)
        insertVoice(235, userId = 12)
        insertVoice(236, answer = "  ")
        insertVoice(237)
        execute("update questions set question = '  ' where id = 237")
        insertVoice(238, persistExtension = false)
        insertVoice(239, extensionOwner = 11)
        insertVoice(240, status = "ungraded")
        (231L..240L).forEach { id ->
            execute("insert into question_likes values ($id, $id, 7, timestamp with time zone '2026-06-12 00:00:00+00:00')")
        }

        assertThat(repository.findPublicAnsweredById(231)?.recordType).isEqualTo(StudyRecordType.VOICE_TUTOR)
        assertThat(repository.findPublicAnsweredByIdAndLanguage(231, "en")?.id).isEqualTo(231L)
        listOf(232L, 233, 234, 236, 237, 238, 239, 240).forEach { id ->
            assertThat(repository.findPublicAnsweredById(id)).describedAs("voice eligibility for %s", id).isNull()
        }
        val page = repository.findPublicAnsweredByLanguageAndQueryVisibleTo(7, "ko", "voice", PageRequest.of(0, 20))
        val liked = repository.findLikedPublicAnsweredVisibleTo(7, "voice", "ko", 20, 0)
        assertThat(page.content.map { it.id }).containsExactly(231L)
        assertThat(page.totalElements).isEqualTo(1)
        assertThat(liked.content.map { it.id }).containsExactly(231L)
        assertThat(liked.totalElements).isEqualTo(1)
        assertThat(repository.findPublicAnsweredByIds((231L..240L).toList()).map { it.id })
            .containsExactlyInAnyOrder(231L, 235L) // Viewer-specific block applies in the visible page/detail service.
    }

    @Test
    fun `global sharing changes and record deletion cannot publish a migrated private voice record`(): Unit = runBlocking {
        insertVoice(241)
        insertVoice(242, publicQuestion = false)
        execute("update users set allow_public_questions = false where id = 10")
        assertThat(repository.findPublicAnsweredById(241)).isNull()
        execute("update users set allow_public_questions = true where id = 10")
        assertThat(repository.findPublicAnsweredById(241)?.id).isEqualTo(241L)
        assertThat(repository.findPublicAnsweredById(242)).isNull()
        assertThat(repository.softDelete(241, 10, Instant.parse("2026-06-13T00:00:00Z"))).isEqualTo(1)
        assertThat(repository.findPublicAnsweredById(241)).isNull()
        assertThat(repository.findOwnedRecordsByIds(10, listOf(241))).isEmpty()
        assertThat(repository.findByIdAndUserIdAndDeletedAtIsNull(241, 10)).isNull()
    }

    @Test
    fun `grading watchdog event updates and generation rollback cannot modify voice records`(): Unit = runBlocking {
        insertVoice(251)
        execute("update questions set grading_request_id = 'forged', grading_status = 'QUEUED', grading_requested_at = timestamp with time zone '2026-06-01 00:00:00+00:00' where id = 251")
        val cutoff = Instant.parse("2026-06-13T00:00:00Z")

        assertThat(repository.findStalledGradings(cutoff, 20)).isEmpty()
        assertThat(repository.failStalledGrading(251, "forged", cutoff, "Must not write", cutoff)).isFalse()
        assertThat(repository.updateGradingLastEventId(251, "forged", 777)).isFalse()
        assertThat(repository.findByGradingRequestIdAndUserIdAndDeletedAtIsNull("forged", 10)).isNull()
        assertThat(repository.deleteGeneratedForRollback(251, 10)).isZero()
        val retained = requireNotNull(repository.findQuestionById(251))
        assertThat(retained.status).isEqualTo(QuestionStatus.COMPLETED)
        assertThat(retained.gradingError).isNull()
        assertThat(retained.gradingLastEventId).isNull()
    }

    @Test
    fun `voice cannot enter question embeddings and legacy stray embeddings are excluded`(): Unit = runBlocking {
        insertVoice(261, studyId = 501, topic = "Voice embeddings")
        insertQuestion(262, 10, "Voice embeddings", "graded", "Answer", publicQuestion = true)
        execute("update questions set study_id = 501 where id = 262")
        val embeddings = QuestionEmbeddingRepository(entityTemplate)
        assertThatThrownBy {
            runBlocking { embeddings.save(261, 10, 501, "Voice embeddings", "Voice source", listOf(0.2f)) }
        }.isInstanceOf(IllegalArgumentException::class.java)
        listOf(261L, 262L).forEach { id ->
            execute("insert into question_embeddings values ($id, 10, 501, 'Voice embeddings', 'voice embeddings', 'Question $id', '0.2,0.4', timestamp with time zone '2026-06-10 00:00:00+00:00', timestamp with time zone '2026-06-10 00:00:00+00:00')")
        }
        assertThat(embeddings.findRecentByStudyIdAndTopic(501, "Voice embeddings", 20).map { it.questionId })
            .containsExactly(262L)
    }

    private suspend fun insertVoice(
        id: Long,
        userId: Long = 10,
        studyId: Long = 501,
        answer: String = "원문 답변",
        score: Int? = null,
        publicQuestion: Boolean = true,
        deleted: Boolean = false,
        status: String = "completed",
        persistExtension: Boolean = true,
        extensionOwner: Long = userId,
        topic: String = "Voice record",
    ) {
        insertQuestion(id, userId, topic, status, answer, publicQuestion, deleted)
        if (persistExtension) execute("insert into voice_study_learning_records values (${1000 + id}, $extensionOwner)")
        execute(
            """
            update questions set record_type = 'VOICE_TUTOR', voice_record_id = ${1000 + id},
                study_id = $studyId, score = ${score ?: "null"}, source = 'voice_tutor',
                created_at = timestamp with time zone '2026-06-11 00:00:00+00:00',
                answered_at = timestamp with time zone '2026-06-11 00:01:00+00:00'
            where id = $id
            """.trimIndent(),
        )
        execute("insert into question_search values ($id, 'ko', '$topic', 'Voice question $id', 'Voice answer', 'Voice feedback', 'Voice depth')")
    }
    @Test
    fun `ranked following preserves canonical voice eligibility and exact page counts`(): Unit = runBlocking {
        execute("insert into users (id, display_name, allow_public_questions) values (7, 'Viewer', true)")
        TopicSubscriptionPersistenceAdapter(database).replaceTopics(7, listOf("Voice record"))
        insertVoice(301)
        insertVoice(302, publicQuestion = false)
        insertVoice(303, deleted = true)
        insertVoice(304, status = "grading")
        insertVoice(305, answer = " ")
        insertVoice(306, persistExtension = false)
        insertVoice(307, extensionOwner = 20)
        insertVoice(308, userId = 12)
        val page = feed(viewer = 7, scope = PublicFeedScope.FOLLOWING, limit = 1)
        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content.map { it.id }).containsExactly(301L)
        assertThat(page.content.single().recordType).isEqualTo(StudyRecordType.VOICE_TUTOR)
        assertThat(feed(viewer = 7, scope = PublicFeedScope.FOLLOWING, limit = 1, offset = 1).content).isEmpty()
    }

    @Test
    fun `recommended feed promotes subscriptions and orders their engagement before exact pagination`(): Unit = runBlocking {
        execute("insert into users (id, display_name, allow_public_questions) values (7, 'Viewer', true)")
        TopicSubscriptionPersistenceAdapter(database).replaceTopics(7, listOf("Swift UI"))
        execute("update questions set topic = 'swift-ui' where id in (101, 102, 103)")
        execute("insert into question_stats values (101, 100, 1), (102, 10, 50), (103, 0, 0), (110, 1000000, 1000000)")
        val first = feed(viewer = 7, limit = 2)
        val second = feed(viewer = 7, limit = 2, offset = 2)
        val exact = feed(viewer = 7, limit = 2, offset = 1)
        assertThat(first.totalElements).isEqualTo(4)
        assertThat(first.content.map { it.id }).containsExactly(102L, 101L)
        assertThat(second.content.map { it.id }).containsExactly(103L, 110L)
        assertThat(exact.pageable.offset).isEqualTo(1)
        assertThat(exact.content.map { it.id }).containsExactly(101L, 103L)
    }

    @Test
    fun `following matches normalized localized topics and keeps all visibility exclusions before counts`(): Unit = runBlocking {
        execute("insert into users (id, display_name, allow_public_questions) values (7, 'Viewer', true)")
        TopicSubscriptionPersistenceAdapter(database).replaceTopics(7, listOf("Data Structures"))
        execute("update question_search set topic = 'DATA_structures'")
        execute("insert into question_search values (110, 'en', 'Data Structures', 'Visible english', '', '', '')")
        execute("insert into question_stats values (104, 1000000, 1000000), (107, 1000000, 1000000)")
        val page = feed(viewer = 7, scope = PublicFeedScope.FOLLOWING, sort = PublicFeedSort.LATEST)
        assertThat(page.totalElements).isEqualTo(4)
        assertThat(page.content.map { it.id }).containsExactly(110L, 103L, 102L, 101L)
        val english = feed(viewer = 7, scope = PublicFeedScope.FOLLOWING, query = "Visible english", language = "en")
        assertThat(english.totalElements).isEqualTo(1)
        assertThat(english.content.map { it.id }).containsExactly(110L)
        assertThat(feed(viewer = 7, scope = PublicFeedScope.FOLLOWING, query = "Visible english", language = "ko").content).isEmpty()
    }

    @Test
    fun `anonymous recommendations use global popularity and following without subscriptions is empty`(): Unit = runBlocking {
        execute("insert into question_stats values (101, 100, 1), (102, 10, 50)")
        assertThat(feed().content.map { it.id }.take(2)).containsExactly(102L, 101L)
        assertThat(feed(scope = PublicFeedScope.FOLLOWING).totalElements).isZero()
        assertThat(feed(viewer = 8, scope = PublicFeedScope.FOLLOWING).totalElements).isZero()
    }

    @Test
    fun `explicit popularity sorts rank by genuine views or likes with deterministic ties`(): Unit = runBlocking {
        execute("insert into question_stats values (101, 100, 1), (102, 10, 50)")
        assertThat(feed(viewer = 7, sort = PublicFeedSort.VIEWS).content.map { it.id }).containsExactly(101L, 102L, 110L, 103L)
        assertThat(feed(viewer = 7, sort = PublicFeedSort.LIKES).content.map { it.id }).containsExactly(102L, 101L, 110L, 103L)
        assertThat(feed(viewer = 7, sort = PublicFeedSort.LATEST).content.map { it.id }).containsExactly(110L, 103L, 102L, 101L)
    }

    @Test
    fun `recommended freshness decay gives recent content a chance against stale engagement`(): Unit = runBlocking {
        execute("update questions set graded_at = current_timestamp, created_at = current_timestamp where id = 101")
        execute("update questions set graded_at = timestamp with time zone '2020-01-01 00:00:00+00:00' where id = 102")
        execute("insert into question_stats values (101, 10, 2), (102, 1000000, 1000000)")
        assertThat(feed(viewer = 7).content.first().id).isEqualTo(101L)
    }

    @Test
    fun `subscriptions replace only their account and cascade on withdrawal`(): Unit = runBlocking {
        val adapter = TopicSubscriptionPersistenceAdapter(database)
        adapter.replaceTopics(10, listOf("Swift UI", "Kotlin"))
        adapter.replaceTopics(11, listOf("Redis"))
        assertThat(adapter.findTopics(10)).containsExactly("Swift UI", "Kotlin")
        adapter.replaceTopics(10, listOf("Python"))
        assertThat(adapter.findTopics(10)).containsExactly("Python")
        assertThat(adapter.findTopics(11)).containsExactly("Redis")
        adapter.replaceTopics(10, emptyList())
        assertThat(adapter.findTopics(10)).isEmpty()
        execute("delete from users where id = 11")
        assertThat(adapter.findTopics(11)).isEmpty()
    }

    private suspend fun feed(
        viewer: Long? = null, sort: PublicFeedSort = PublicFeedSort.RECOMMENDED,
        scope: PublicFeedScope = PublicFeedScope.ALL, query: String? = null,
        language: String = "ko", limit: Int = 20, offset: Int = 0,
    ) = repository.findPersonalizedPublicAnswered(viewer, query, language, sort, scope, limit, offset)

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

    @WritingConverter
    private object InstantToOffsetDateTime : Converter<Instant, OffsetDateTime> {
        override fun convert(source: Instant): OffsetDateTime = source.atOffset(ZoneOffset.UTC)
    }

    @ReadingConverter
    private object StringToSupportedLanguage : Converter<String, SupportedLanguage> {
        override fun convert(source: String): SupportedLanguage = SupportedLanguage.fromDatabaseValue(source)
    }

    @ReadingConverter
    private object StringToQuestionStatus : Converter<String, QuestionStatus> {
        override fun convert(source: String): QuestionStatus = QuestionStatus.fromDatabaseValue(source)
    }

    @WritingConverter
    private object QuestionStatusToString : Converter<QuestionStatus, String> {
        override fun convert(source: QuestionStatus): String = source.databaseValue
    }

    @ReadingConverter
    private object StringToQuestionSource : Converter<String, QuestionSource> {
        override fun convert(source: String): QuestionSource = QuestionSource.fromDatabaseValue(source)
    }

    @ReadingConverter
    private object StringToStudyRecordType : Converter<String, StudyRecordType> {
        override fun convert(source: String): StudyRecordType = StudyRecordType.valueOf(source)
    }
}
