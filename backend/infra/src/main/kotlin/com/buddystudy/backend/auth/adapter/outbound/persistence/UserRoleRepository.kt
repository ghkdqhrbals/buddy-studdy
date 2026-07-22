package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.UserRoleEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface UserRoleRepository : CoroutineCrudRepository<UserRoleEntity, Long> {
    suspend fun existsByUserIdAndRoleId(userId: Long, roleId: Long): Boolean
    suspend fun countByUserIdAndRoleId(userId: Long, roleId: Long): Long
}
