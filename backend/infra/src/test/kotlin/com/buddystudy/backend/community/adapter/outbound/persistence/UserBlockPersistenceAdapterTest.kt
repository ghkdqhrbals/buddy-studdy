package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.community.domain.entity.UserBlockEntity
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant

class UserBlockPersistenceAdapterTest {
    private val connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///user-block-insert;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    )
    private val databaseClient = DatabaseClient.create(connectionFactory)
    private val adapter = UserBlockPersistenceAdapter(
        repository = mock(UserBlockRepository::class.java),
        databaseClient = databaseClient,
        connectionFactory = connectionFactory,
    )

    @BeforeEach
    fun setUp() {
        runBlocking {
            databaseClient.sql("drop table if exists user_blocks").fetch().rowsUpdated().awaitSingle()
            databaseClient.sql(
                """
                create table user_blocks (
                    id bigint auto_increment primary key,
                    blocker_user_id bigint not null,
                    blocked_user_id bigint not null,
                    created_at timestamp with time zone not null,
                    constraint uq_user_blocks_pair unique (blocker_user_id, blocked_user_id),
                    constraint ck_user_blocks_not_self check (blocker_user_id <> blocked_user_id)
                )
                """.trimIndent(),
            ).fetch().rowsUpdated().awaitSingle()
        }
    }

    @Test
    fun `concurrent inserts create one block on the H2 fallback dialect`(): Unit = runBlocking {
        val results = coroutineScope {
            List(8) {
                async(Dispatchers.Default) {
                    adapter.insertIfAbsent(
                        UserBlockEntity(
                            blockerUserId = 7,
                            blockedUserId = 10,
                            createdAt = Instant.parse("2026-08-14T00:00:00Z"),
                        ),
                    )
                }
            }.awaitAll()
        }

        assertThat(results.count { it }).isEqualTo(1)
        assertThat(
            databaseClient.sql("select count(*) from user_blocks")
                .map { row, _ -> row.get(0, java.lang.Long::class.java)!!.toLong() }
                .one()
                .awaitSingle(),
        ).isEqualTo(1)
    }
}
