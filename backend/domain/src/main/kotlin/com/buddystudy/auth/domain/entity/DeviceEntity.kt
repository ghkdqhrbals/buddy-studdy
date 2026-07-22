package com.buddystudy.auth.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("devices")
class DeviceEntity(
    @Id
    var id: Long = 0,
    var deviceId: String = "",
    var clientSecretHash: String = "",
    var userId: Long? = null,
    var googleSessionExpiresAt: Instant? = null,
    var apnsToken: String = "",
    var platform: String = "ios",
    var apnsEnvironment: String = "production",
    var language: String = "ko",
    var timezone: String = "Asia/Seoul",
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var lastSeenAt: Instant = Instant.now(),
)
