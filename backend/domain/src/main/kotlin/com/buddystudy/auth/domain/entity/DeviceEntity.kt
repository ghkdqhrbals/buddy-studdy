package com.buddystudy.auth.domain.entity

import com.buddystudy.common.domain.SupportedLanguage
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("devices")
class DeviceEntity(
    @Id
    var id: Long = 0,
    var deviceId: String = "",
    var installationKeyHash: String? = null,
    var clientSecretHash: String = "",
    var userId: Long? = null,
    var googleSessionExpiresAt: Instant? = null,
    var apnsToken: String = "",
    var platform: DevicePlatform = DevicePlatform.IOS,
    var apnsEnvironment: ApnsEnvironment = ApnsEnvironment.PRODUCTION,
    var language: SupportedLanguage = SupportedLanguage.KOREAN,
    var timezone: String = "Asia/Seoul",
    var appVersion: String? = null,
    var appBuild: String? = null,
    var appVersionSeenAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var lastSeenAt: Instant = Instant.now(),
)
