package com.buddystudy.auth.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("role_permissions")
class RolePermissionEntity(
    @Id
    var id: Long = 0,
    var roleId: Long = 0,
    var permissionId: Long = 0,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
