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
    name = "devices",
    indexes = [
        Index(name = "idx_devices_device_id", columnList = "device_id"),
        Index(name = "idx_devices_user_id", columnList = "user_id"),
    ]
)
class DeviceEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "device_id", nullable = false, unique = true, length = 191)
    var deviceId: String = "",
    @Column(name = "client_secret_hash", nullable = false, length = 191)
    var clientSecretHash: String = "",
    @Column(name = "user_id")
    var userId: Long? = null,
    @Column(name = "google_session_expires_at")
    var googleSessionExpiresAt: Instant? = null,
    @Column(name = "apns_token", nullable = false, length = 191)
    var apnsToken: String = "",
    @Column(nullable = false, length = 32)
    var platform: String = "ios",
    @Column(name = "apns_environment", nullable = false, length = 32)
    var apnsEnvironment: String = "production",
    @Column(nullable = false, length = 16)
    var language: String = "ko",
    @Column(nullable = false, length = 64)
    var timezone: String = "Asia/Seoul",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.now(),
)
