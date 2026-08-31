package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.config.BuddyStudyProperties
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
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

class StudyRepositoryChildPageTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///owned-child-study-page;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val repository: StudyRepository

    init {
        val conversions = R2dbcCustomConversions.of(MySqlDialect.INSTANCE, OffsetDateTimeToInstant)
        val mappingContext = RelationalMappingContext().also {
            it.setSimpleTypeHolder(conversions.simpleTypeHolder)
        }
        repository = StudyRepository(
            R2dbcEntityTemplate(database, MySqlDialect.INSTANCE, MappingR2dbcConverter(mappingContext, conversions)),
            BuddyStudyProperties(),
        )
    }

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        database.sql("drop table if exists studies").fetch().rowsUpdated().awaitSingle()
        database.sql(
            """
            create table studies (
                id bigint primary key,
                device_id varchar(191) not null default 'fixture-device',
                user_id bigint not null,
                parent_study_id bigint,
                sort_order integer not null default 0,
                topic varchar(255) not null,
                difficulty_level integer not null default 5,
                interval_minutes integer not null default 15,
                enabled boolean not null default false,
                active_for_questions boolean not null default true,
                notification_sound varchar(100),
                custom_prompt text not null default '',
                openai_model varchar(100) not null default 'fixture-model',
                max_history_count integer not null default 100,
                next_due_at timestamp with time zone,
                schedule_claimed_until timestamp with time zone,
                last_sent_at timestamp with time zone,
                last_error text,
                created_at timestamp with time zone not null default current_timestamp,
                updated_at timestamp with time zone not null default current_timestamp
            )
            """.trimIndent(),
        ).fetch().rowsUpdated().awaitSingle()
        database.sql("create index idx_studies_user_parent_order on studies(user_id, parent_study_id, sort_order, id)")
            .fetch().rowsUpdated().awaitSingle()

        insert(10, 7, null, "Root")
        insert(14, 7, 10, "SQL", sortOrder = 2)
        insert(13, 7, 10, "Model matching", sortOrder = 1, model = "redis-fixture")
        insert(12, 7, 10, "Prompt matching", sortOrder = 1, prompt = "Use Redis examples")
        insert(11, 7, 10, "Redis topic")
        insert(21, 7, 20, "Redis other parent")
        insert(22, 7, 11, "Redis grandchild")
        insert(23, 99, 10, "Redis different owner")
        insert(24, 99, 10, "Other owner prompt", prompt = "Redis")
        insert(25, 7, 20, "Other parent model", model = "redis-fixture")
    }

    @Test
    fun `database child page bounds both selection and count by owner and direct parent`(): Unit = runBlocking {
        val first = repository.findByUserIdAndParentStudyId(7, 10, null, PageRequest.of(0, 2))
        val second = repository.findByUserIdAndParentStudyId(7, 10, null, PageRequest.of(1, 2))

        assertThat(first.content.map { it.id }).containsExactly(11L, 12L)
        assertThat(second.content.map { it.id }).containsExactly(13L, 14L)
        assertThat(first.totalElements).isEqualTo(4)
        assertThat(second.totalElements).isEqualTo(4)
        assertThat(first.content + second.content).allSatisfy { study ->
            assertThat(study.userId).isEqualTo(7)
            assertThat(study.parentStudyId).isEqualTo(10)
        }
    }

    @Test
    fun `non-page-aligned offset is not rounded down by the database query`(): Unit = runBlocking {
        val exactOffsetPage = object : Pageable by PageRequest.of(0, 2) {
            override fun getOffset(): Long = 1
        }
        val page = repository.findByUserIdAndParentStudyId(7, 10, null, exactOffsetPage)
        assertThat(page.content.map { it.id }).containsExactly(12L, 13L)
        assertThat(page.totalElements).isEqualTo(4)
    }

    @Test
    fun `database search groups all matching fields inside owner and parent restrictions`(): Unit = runBlocking {
        val first = repository.findByUserIdAndParentStudyId(7, 10, "REDIS", PageRequest.of(0, 2))
        val second = repository.findByUserIdAndParentStudyId(7, 10, "REDIS", PageRequest.of(1, 2))

        assertThat(first.content.map { it.id }).containsExactly(11L, 12L)
        assertThat(second.content.map { it.id }).containsExactly(13L)
        assertThat(first.totalElements).isEqualTo(3)
        assertThat(second.totalElements).isEqualTo(3)
    }

    @Test
    fun `offset past last child preserves filtered total without returning unrelated rows`(): Unit = runBlocking {
        val page = repository.findByUserIdAndParentStudyId(7, 10, "redis", PageRequest.of(5, 2))

        assertThat(page.content).isEmpty()
        assertThat(page.totalElements).isEqualTo(3)
    }

    @Test
    fun `no matching child returns zero while an empty query preserves the sibling page`(): Unit = runBlocking {
        val missing = repository.findByUserIdAndParentStudyId(7, 10, "no such topic", PageRequest.of(0, 10))
        val blank = repository.findByUserIdAndParentStudyId(7, 10, " ", PageRequest.of(0, 10))

        assertThat(missing.content).isEmpty()
        assertThat(missing.totalElements).isZero()
        assertThat(blank.content.map { it.id }).containsExactly(11L, 12L, 13L, 14L)
        assertThat(blank.totalElements).isEqualTo(4)
    }

    private suspend fun insert(
        id: Long,
        userId: Long,
        parentStudyId: Long?,
        topic: String,
        sortOrder: Int = 0,
        prompt: String = "",
        model: String = "fixture-model",
    ) {
        var spec = database.sql(
            """
            insert into studies(id, user_id, parent_study_id, topic, sort_order, custom_prompt, openai_model)
            values(:id, :userId, :parentId, :topic, :sortOrder, :prompt, :model)
            """.trimIndent(),
        ).bind("id", id).bind("userId", userId).bind("topic", topic)
            .bind("sortOrder", sortOrder).bind("prompt", prompt).bind("model", model)
        spec = if (parentStudyId == null) spec.bindNull("parentId", java.lang.Long::class.java)
            else spec.bind("parentId", parentStudyId)
        spec.fetch().rowsUpdated().awaitSingle()
    }

    @ReadingConverter
    private object OffsetDateTimeToInstant : Converter<OffsetDateTime, Instant> {
        override fun convert(source: OffsetDateTime): Instant = source.toInstant()
    }
}
