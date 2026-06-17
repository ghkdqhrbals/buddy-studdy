package com.buddystuddy.backend.auth.application.service

import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.auth.domain.Account
import com.buddystuddy.auth.domain.AccountDevice
import com.buddystuddy.auth.domain.AccountUser
import com.buddystuddy.auth.domain.DeviceAttachment
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.auth.domain.entity.DeviceEntity
import com.buddystuddy.auth.domain.entity.UserDeviceEntity
import com.buddystuddy.account.domain.entity.UserEntity
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

    fun findSession(sessionId: Long, userId: Long): UserDeviceEntity? =
        userDevices.findByIdAndUserId(sessionId, userId)

    fun saveSessionState(session: UserDeviceEntity): UserDeviceEntity =
        userDevices.save(session)

    fun activeSessions(userId: Long): List<UserDeviceEntity> =
        userDevices.findActiveByUserId(userId)

    fun ensureAnonymousUser(device: DeviceEntity): UserEntity {
        val now = Instant.now()
        val user = users.findByProviderAndProviderId("ANONYMOUS", device.deviceId)
            ?: users.save(
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
        device.apply(Account.of(user.toAccountUser(), device.toAccountDevice()).attachDevice(now))
        return user
    }

    fun saveSession(userId: Long, deviceId: String, now: Instant, expiresAt: Instant?): UserDeviceEntity {
        val session = userDevices.findByUserIdAndDeviceId(userId, deviceId)
            ?: UserDeviceEntity(userId = userId, deviceId = deviceId, createdAt = now)
        session.lastLoginAt = now
        session.lastSeenAt = now
        session.updatedAt = now
        session.sessionExpiresAt = expiresAt
        session.loggedOutAt = null
        session.revokedAt = null
        val saved = userDevices.save(session)
        revokeOtherActiveSessions(userId, saved.deviceId, now)
        return saved
    }

    private fun revokeOtherActiveSessions(userId: Long, currentDeviceId: String, now: Instant) {
        userDevices.findActiveByUserId(userId)
            .filter { it.deviceId != currentDeviceId }
            .forEach { session ->
                session.revokedAt = now
                session.updatedAt = now
                userDevices.save(session)
            }
    }

    private fun UserEntity.toAccountUser() = AccountUser(id = id, status = status)

    private fun DeviceEntity.toAccountDevice() = AccountDevice(deviceId = deviceId, userId = userId)

    private fun DeviceEntity.apply(attachment: DeviceAttachment) {
        userId = attachment.userId
        updatedAt = attachment.updatedAt
    }
}
