package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.UserRoleEntity
import org.springframework.data.jpa.repository.JpaRepository

interface UserRoleRepository : JpaRepository<UserRoleEntity, Long> {
    fun existsByUserIdAndRoleId(userId: Long, roleId: Long): Boolean
    fun countByUserIdAndRoleId(userId: Long, roleId: Long): Long
}
