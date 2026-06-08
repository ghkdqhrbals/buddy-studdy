package com.buddystuddy.backend.auth.application.service

import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.auth.domain.AccountAggregate
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.domain.DeviceEntity
import com.buddystuddy.backend.domain.UserDeviceEntity
import com.buddystuddy.backend.domain.UserEntity
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class AccountSessionManager(
    private val users: UserPort,
    private val devices: DevicePort,
    private val userDevices: UserDevicePort,
) {
    fun device(deviceId: String): DeviceEntity = devices.findByDeviceId(deviceId)
        ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.DEVICE_NOT_FOUND, "Device not found.")

    fun ensureAnonymousUser(device: DeviceEntity): UserEntity {
        val now = Instant.now()
        val user = users.save(
            UserEntity(
                provider = "ANONYMOUS",
                providerId = device.deviceId,
                status = "ANONYMOUS",
                displayName = "Buddy",
                avatarColorSeed = "avatar-color-gray",
                createdAt = now,
                updatedAt = now,
            )
        )
        AccountAggregate.of(user, device).attachDevice(now)
        return user
    }

    fun saveSession(userId: Long, deviceId: String, now: Instant, expiresAt: Instant?): UserDeviceEntity {
        val session = userDevices.findByUserIdAndDeviceId(userId, deviceId)
            ?: UserDeviceEntity(userId = userId, deviceId = deviceId, createdAt = now)
        session.lastLoginAt = now
        session.lastSeenAt = now
        session.updatedAt = now
        session.sessionExpiresAt = expiresAt
        return userDevices.save(session)
    }
}
