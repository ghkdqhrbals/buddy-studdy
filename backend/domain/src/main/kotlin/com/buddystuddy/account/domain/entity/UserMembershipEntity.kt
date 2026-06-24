package com.buddystuddy.account.domain.entity

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
    name = "user_memberships",
    indexes = [
        Index(name = "idx_user_memberships_user_status", columnList = "user_id,status"),
    ],
)
class UserMembershipEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,
    @Column(nullable = false, length = 32)
    var tier: String = "TIER1",
    @Column(nullable = false, length = 32)
    var status: String = "ACTIVE",
    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),
    @Column(name = "expires_at")
    var expiresAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
