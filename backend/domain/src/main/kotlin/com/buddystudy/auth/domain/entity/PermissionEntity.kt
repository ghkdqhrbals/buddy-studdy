package com.buddystudy.auth.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "permissions",
    indexes = [Index(name = "idx_permissions_code", columnList = "code", unique = true)],
)
class PermissionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(nullable = false, unique = true, length = 120)
    var code: String = "",
    @Column(nullable = false, length = 255)
    var description: String = "",
    @Column(name = "requires_active_account", nullable = false)
    var requiresActiveAccount: Boolean = false,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
