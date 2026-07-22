package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.RolePermissionEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface RolePermissionRepository : CoroutineCrudRepository<RolePermissionEntity, Long> {
    suspend fun existsByRoleIdAndPermissionId(roleId: Long, permissionId: Long): Boolean
}
