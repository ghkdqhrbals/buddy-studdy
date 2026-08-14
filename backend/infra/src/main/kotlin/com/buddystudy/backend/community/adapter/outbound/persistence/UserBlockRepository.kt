package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.UserBlockPort
import com.buddystudy.community.domain.entity.UserBlockEntity
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component

interface UserBlockRepository : CoroutineCrudRepository<UserBlockEntity, Long> {
    suspend fun existsByBlockerUserIdAndBlockedUserId(blockerUserId: Long, blockedUserId: Long): Boolean

    @Query("select blocked_user_id from user_blocks where blocker_user_id = :blockerUserId")
    suspend fun findBlockedUserIds(blockerUserId: Long): List<Long>

    suspend fun deleteByBlockerUserIdAndBlockedUserId(blockerUserId: Long, blockedUserId: Long): Long
}

@Component
class UserBlockPersistenceAdapter(
    private val repository: UserBlockRepository,
    private val databaseClient: DatabaseClient,
    connectionFactory: ConnectionFactory,
) : UserBlockPort {
    private val insertIfAbsentSql = when {
        connectionFactory.metadata.name.contains("mysql", ignoreCase = true) -> MYSQL_INSERT_IF_ABSENT
        connectionFactory.metadata.name.contains("postgres", ignoreCase = true) -> POSTGRES_INSERT_IF_ABSENT
        else -> STANDARD_INSERT
    }

    override suspend fun insertIfAbsent(entity: UserBlockEntity): Boolean = try {
        databaseClient.sql(insertIfAbsentSql)
            .bind("blockerUserId", entity.blockerUserId)
            .bind("blockedUserId", entity.blockedUserId)
            .bind("createdAt", entity.createdAt)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0
    } catch (_: DuplicateKeyException) {
        // H2 and other fallback dialects use the unique pair as the atomic conflict gate.
        false
    }

    override suspend fun exists(blockerUserId: Long, blockedUserId: Long): Boolean =
        repository.existsByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)

    override suspend fun findBlockedUserIds(blockerUserId: Long): Set<Long> =
        repository.findBlockedUserIds(blockerUserId).toSet()

    override suspend fun delete(blockerUserId: Long, blockedUserId: Long): Long =
        repository.deleteByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)

    private companion object {
        val STANDARD_INSERT =
            """
            insert into user_blocks (blocker_user_id, blocked_user_id, created_at)
            values (:blockerUserId, :blockedUserId, :createdAt)
            """.trimIndent()

        val MYSQL_INSERT_IF_ABSENT =
            """
            insert ignore into user_blocks (blocker_user_id, blocked_user_id, created_at)
            values (:blockerUserId, :blockedUserId, :createdAt)
            """.trimIndent()

        val POSTGRES_INSERT_IF_ABSENT =
            """
            insert into user_blocks (blocker_user_id, blocked_user_id, created_at)
            values (:blockerUserId, :blockedUserId, :createdAt)
            on conflict (blocker_user_id, blocked_user_id) do nothing
            """.trimIndent()
    }
}
