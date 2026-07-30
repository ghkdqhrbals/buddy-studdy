package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.UserBlockPort
import com.buddystudy.community.domain.entity.UserBlockEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
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
) : UserBlockPort {
    override suspend fun save(entity: UserBlockEntity): UserBlockEntity = repository.save(entity)

    override suspend fun exists(blockerUserId: Long, blockedUserId: Long): Boolean =
        repository.existsByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)

    override suspend fun findBlockedUserIds(blockerUserId: Long): Set<Long> =
        repository.findBlockedUserIds(blockerUserId).toSet()

    override suspend fun delete(blockerUserId: Long, blockedUserId: Long): Long =
        repository.deleteByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)
}
