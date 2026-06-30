package com.buddystudy.auth.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "role_permissions",
    uniqueConstraints = [UniqueConstraint(name = "uq_role_permissions_role_permission", columnNames = ["role_id", "permission_id"])],
    indexes = [
        Index(name = "idx_role_permissions_role_id", columnList = "role_id"),
        Index(name = "idx_role_permissions_permission_id", columnList = "permission_id"),
    ],
)
class RolePermissionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "role_id", nullable = false)
    var roleId: Long = 0,
    @Column(name = "permission_id", nullable = false)
    var permissionId: Long = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
