package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.RolePermissionEntity
import org.springframework.data.jpa.repository.JpaRepository

interface RolePermissionRepository : JpaRepository<RolePermissionEntity, Long> {
    fun existsByRoleIdAndPermissionId(roleId: Long, permissionId: Long): Boolean
}
