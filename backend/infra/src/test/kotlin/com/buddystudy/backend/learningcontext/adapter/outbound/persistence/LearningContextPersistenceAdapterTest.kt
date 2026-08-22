package com.buddystudy.backend.learningcontext.adapter.outbound.persistence

import com.buddystudy.learningcontext.domain.entity.UserLearningContextEntity
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.MySqlDialect
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant

class LearningContextPersistenceAdapterTest {
    private val connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///learning-context;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    )
    private val database = DatabaseClient.create(connectionFactory)
    private val adapter = LearningContextPersistenceAdapter(R2dbcEntityTemplate(database, MySqlDialect.INSTANCE))

    @BeforeEach
    fun setUp() {
        runBlocking {
            execute("drop table if exists user_learning_contexts")
            execute("drop table if exists users")
            execute("create table users (id bigint primary key)")
            execute(
                """
                create table user_learning_contexts (
                    user_id bigint primary key,
                    resume_markdown text null,
                    interests_json text not null,
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone not null,
                    constraint fk_user_learning_contexts_user
                        foreign key (user_id) references users(id) on delete cascade
                )
                """.trimIndent(),
            )
            execute("insert into users (id) values (1), (2), (42)")
        }
    }

    @Test
    fun `save inserts and then updates a single context per user while preserving creation time`(): Unit = runBlocking {
        val createdAt = Instant.parse("2026-08-22T01:02:03Z")
        val firstUpdatedAt = Instant.parse("2026-08-22T02:03:04Z")
        adapter.save(
            UserLearningContextEntity(
                userId = 42,
                resumeMarkdown = "# 이력서\nKotlin · WebFlux",
                interestsJson = "[\"Kotlin\",\"분산 시스템\"]",
                createdAt = createdAt,
                updatedAt = firstUpdatedAt,
            ),
        )

        val replacementCreatedAt = Instant.parse("2030-01-01T00:00:00Z")
        val secondUpdatedAt = Instant.parse("2026-08-22T03:04:05Z")
        adapter.save(
            UserLearningContextEntity(
                userId = 42,
                resumeMarkdown = null,
                interestsJson = "[\"LLM 도구\"]",
                createdAt = replacementCreatedAt,
                updatedAt = secondUpdatedAt,
            ),
        )

        val stored = adapter.findByUserId(42)
        assertThat(stored).isNotNull
        assertThat(stored!!.resumeMarkdown).isNull()
        assertThat(stored.interestsJson).isEqualTo("[\"LLM 도구\"]")
        assertThat(stored.createdAt).isEqualTo(createdAt)
        assertThat(stored.updatedAt).isEqualTo(secondUpdatedAt)
        assertThat(rowCount()).isEqualTo(1)
    }

    @Test
    fun `delete removes only the requested user's context`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-22T04:05:06Z")
        adapter.save(UserLearningContextEntity(userId = 1, interestsJson = "[]", createdAt = now, updatedAt = now))
        adapter.save(UserLearningContextEntity(userId = 2, interestsJson = "[\"SwiftUI\"]", createdAt = now, updatedAt = now))

        assertThat(adapter.deleteByUserId(1)).isEqualTo(1)
        assertThat(adapter.deleteByUserId(1)).isZero()
        assertThat(adapter.findByUserId(1)).isNull()
        assertThat(adapter.findByUserId(2)?.interestsJson).isEqualTo("[\"SwiftUI\"]")
    }

    @Test
    fun `deleting a user cascades to its learning context`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-22T05:06:07Z")
        adapter.save(
            UserLearningContextEntity(
                userId = 42,
                resumeMarkdown = "# Resume",
                interestsJson = "[\"Kotlin\"]",
                createdAt = now,
                updatedAt = now,
            ),
        )

        execute("delete from users where id = 42")

        assertThat(adapter.findByUserId(42)).isNull()
    }

    private suspend fun rowCount(): Long =
        database.sql("select count(*) from user_learning_contexts")
            .map { row, _ -> row.get(0, java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    private suspend fun execute(sql: String) {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
    }
}
