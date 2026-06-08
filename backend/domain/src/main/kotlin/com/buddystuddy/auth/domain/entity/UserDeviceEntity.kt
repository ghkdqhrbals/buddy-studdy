package com.buddystuddy.auth.domain.entity

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
    name = "user_devices",
    uniqueConstraints = [UniqueConstraint(name = "uq_user_devices_user_device", columnNames = ["user_id", "device_id"])],
    indexes = [
        Index(name = "idx_user_devices_user_id", columnList = "user_id"),
        Index(name = "idx_user_devices_device_id", columnList = "device_id"),
    ]
)
class UserDeviceEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,
    @Column(name = "device_id", nullable = false, length = 191)
    var deviceId: String = "",
    @Column(name = "session_expires_at")
    var sessionExpiresAt: Instant? = null,
    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.now(),
)
