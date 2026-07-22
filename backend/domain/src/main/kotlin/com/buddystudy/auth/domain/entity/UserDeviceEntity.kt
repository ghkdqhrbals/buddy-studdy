package com.buddystudy.auth.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("user_devices")
class UserDeviceEntity(
    @Id
    var id: Long = 0,
    var userId: Long = 0,
    var deviceId: String = "",
    var sessionExpiresAt: Instant? = null,
    var lastLoginAt: Instant? = null,
    var loggedOutAt: Instant? = null,
    var revokedAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var lastSeenAt: Instant = Instant.now(),
) {
    fun isActive(now: Instant = Instant.now()): Boolean =
        loggedOutAt == null &&
            revokedAt == null &&
            (sessionExpiresAt == null || sessionExpiresAt!!.isAfter(now))
}
