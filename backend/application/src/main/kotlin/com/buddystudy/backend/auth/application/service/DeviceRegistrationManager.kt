package com.buddystudy.backend.auth.application.service

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.auth.domain.entity.ApnsEnvironment
import com.buddystudy.auth.domain.entity.DevicePlatform
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.auth.application.model.DeviceRegisterResponse
import com.buddystudy.backend.auth.application.permission.Roles
import com.buddystudy.backend.auth.application.port.inbound.RegisterDeviceCommand
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.auth.sha256
import com.buddystudy.study.domain.QuestionLanguage
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class DeviceRegistrationManager(
    private val users: UserPort,
    private val devices: DevicePort,
    private val tokenService: TokenProvider,
    private val sessions: AccountSessionManager,
    private val tokens: RandomTokenGenerator,
    private val roles: RoleAssignmentPort,
) {
    @Transactional
    suspend fun register(
        command: RegisterDeviceCommand,
        installationKeyHash: String?,
    ): DeviceRegisterResponse {
        val existingDevice = installationKeyHash?.let { devices.findByInstallationKeyHash(it) }
        val deviceId = existingDevice?.deviceId ?: tokens.create("dev")
        val secret = tokens.create("sec")
        val now = Instant.now()
        val user = existingDevice?.userId
            ?.let { users.findById(it) }
            ?: users.findByProviderAndProviderId("ANONYMOUS", deviceId)
            ?: users.save(
                UserEntity(
                    provider = UserProvider.ANONYMOUS,
                    providerId = deviceId,
                    status = UserStatus.ANONYMOUS,
                    email = "",
                    displayName = "Buddy",
                    avatarColorSeed = "avatar-color-gray",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        val device = existingDevice ?: DeviceEntity(deviceId = deviceId, createdAt = now)
        user.appLanguage = SupportedLanguage.fromLocale(QuestionLanguage.normalize(command.language))
        user.updatedAt = now
        users.save(user)
        device.installationKeyHash = installationKeyHash
        device.clientSecretHash = sha256(secret)
        device.userId = user.id
        device.apnsToken = command.apnsToken
        device.platform = DevicePlatform.fromDatabaseValue(command.platform.lowercase())
        device.apnsEnvironment = ApnsEnvironment.fromDatabaseValue(command.apnsEnvironment.lowercase())
        device.language = SupportedLanguage.fromLocale(command.language)
        device.timezone = command.timezone
        device.appVersion = command.appVersion?.takeIf { it.isNotBlank() }
        device.appBuild = command.appBuild?.takeIf { it.isNotBlank() }
        device.appVersionSeenAt = now
        device.updatedAt = now
        device.lastSeenAt = now
        devices.save(device)

        val anonymous = user.status == UserStatus.ANONYMOUS
        if (anonymous) {
            roles.grantRoleIfMissing(user.id, Roles.ANONYMOUS_USER)
        }
        val sessionExpiresAt = if (anonymous) null else now.plusSeconds(90 * 86_400)
        val session = sessions.saveSession(user.id, device.deviceId, now, sessionExpiresAt)
        val token = tokenService.create(user.id, device.deviceId, session.id, anonymous, user.status.name)
        return DeviceRegisterResponse(device.deviceId, secret, token.first, token.second)
    }
}
