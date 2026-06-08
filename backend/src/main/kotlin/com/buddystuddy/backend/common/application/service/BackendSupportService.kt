package com.buddystuddy.backend.common.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.domain.ScheduleEntity
import com.buddystuddy.backend.domain.UserDeviceEntity
import com.buddystuddy.backend.domain.UserEntity
import com.buddystuddy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.domain.DeviceEntity
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.SchedulePort
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

@Service
class BackendSupportService(
    private val properties: BuddyStuddyProperties,
    private val users: UserPort,
    private val devices: DevicePort,
    private val userDevices: UserDevicePort,
    private val schedules: SchedulePort,
    private val questions: QuestionPort,
    private val cipher: KeyCipher,
) {
    private val random = SecureRandom()

    fun user(id: Long): UserEntity = users.findById(id).orElseThrow {
        ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
    }

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
        device.userId = user.id
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

    fun apiKeyFor(schedule: ScheduleEntity?): String =
        cipher.decrypt(schedule?.openaiApiKeyCipher) ?: properties.openai.apiKey.takeIf { it.isNotBlank() }
        ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "OpenAI API key is not configured.")

    fun scheduleFor(principal: Principal, topic: String?): ScheduleEntity =
        topic?.takeIf { it.isNotBlank() }?.let { schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, it) }
            ?: schedules.findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(principal.deviceId, principal.userId)
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study settings are not configured.")

    fun recentQuestions(principal: Principal): List<String> =
        questions.findVisibleByUser(principal.userId, includePending = true, PageRequest.of(0, 30)).content.map { it.question }

    fun randomToken(prefix: String): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return "$prefix-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
