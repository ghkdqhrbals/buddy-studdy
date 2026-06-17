package com.buddystuddy.backend.auth.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.TokenProvider
import com.buddystuddy.backend.auth.sha256
import com.buddystuddy.auth.domain.Account
import com.buddystuddy.auth.domain.AccountDevice
import com.buddystuddy.auth.domain.AccountUser
import com.buddystuddy.auth.domain.DeviceAttachment
import com.buddystuddy.auth.domain.PushTokenUpdate
import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.auth.application.port.outbound.EmailVerificationCodePort
import com.buddystuddy.backend.auth.application.port.outbound.EmailVerificationSenderPort
import com.buddystuddy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.auth.application.port.inbound.IssueDeviceTokenUseCase
import com.buddystuddy.backend.auth.application.port.inbound.LoginUseCase
import com.buddystuddy.backend.auth.application.port.inbound.RegisterDeviceUseCase
import com.buddystuddy.backend.auth.application.port.inbound.UpdatePushTokenUseCase
import com.buddystuddy.backend.auth.application.port.inbound.EmailLoginCommand
import com.buddystuddy.backend.auth.application.port.inbound.RegisterDeviceCommand
import com.buddystuddy.backend.auth.application.port.inbound.PushTokenCommand
import com.buddystuddy.backend.auth.application.permission.Roles
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.auth.domain.entity.DeviceEntity
import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.application.model.AccessTokenResponse
import com.buddystuddy.backend.auth.application.model.DeviceRegisterResponse
import com.buddystuddy.backend.auth.application.model.EmailVerificationCodeResponse
import com.buddystuddy.backend.auth.application.model.GoogleLoginResponse
import com.buddystuddy.backend.auth.application.model.LoggedInDeviceResponse
import com.buddystuddy.backend.auth.application.model.LoggedInDevicesResponse
import com.buddystuddy.backend.profile.application.model.toProfile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.Duration
import java.security.SecureRandom

@Service
class LoginService(
    private val properties: BuddyStuddyProperties,
    private val users: UserPort,
    private val devices: DevicePort,
    private val tokenService: TokenProvider,
    private val sessions: AccountSessionManager,
    private val tokens: RandomTokenGenerator,
    private val emailCodes: EmailVerificationCodePort,
    private val emailSender: EmailVerificationSenderPort,
    private val roles: RoleAssignmentPort,
) : RegisterDeviceUseCase, IssueDeviceTokenUseCase, LoginUseCase, UpdatePushTokenUseCase {
    private val googleRest = RestClient.builder().baseUrl("https://oauth2.googleapis.com").build()
    private val secureRandom = SecureRandom()

    @Transactional
    override fun register(command: RegisterDeviceCommand): DeviceRegisterResponse {
        val deviceId = tokens.create("dev")
        val secret = tokens.create("sec")
        val now = Instant.now()
        val user = users.save(
            UserEntity(
                provider = "ANONYMOUS",
                providerId = deviceId,
                status = "ANONYMOUS",
                email = "",
                displayName = "Buddy",
                avatarColorSeed = "avatar-color-gray",
                createdAt = now,
                updatedAt = now,
            )
        )
        val device = devices.save(
            DeviceEntity(
                deviceId = deviceId,
                clientSecretHash = sha256(secret),
                userId = user.id,
                apnsToken = command.apnsToken,
                platform = command.platform,
                apnsEnvironment = command.apnsEnvironment,
                language = command.language,
                timezone = command.timezone,
                createdAt = now,
                updatedAt = now,
                lastSeenAt = now,
            )
        )
        roles.grantRoleIfMissing(user.id, Roles.ANONYMOUS_USER)
        val session = sessions.saveSession(user.id, device.deviceId, now, null)
        val token = tokenService.create(user.id, device.deviceId, session.id, true, user.status)
        return DeviceRegisterResponse(device.deviceId, secret, token.first, token.second)
    }

    @Transactional
    override fun token(deviceId: String, clientSecret: String): AccessTokenResponse {
        val principal = authenticateDevice(deviceId, clientSecret)
        val token = tokenService.create(principal.userId, principal.deviceId, principal.sessionId, principal.anonymous, principal.status)
        return AccessTokenResponse(token.first, token.second)
    }

    @Transactional
    override fun authenticateDevice(deviceId: String, clientSecret: String): Principal {
        val device = sessions.device(deviceId)
        if (device.clientSecretHash != sha256(clientSecret)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_DEVICE_CREDENTIALS, "Invalid device credentials.")
        }
        val user = device.userId
            ?.let { users.findById(it).orElseThrow() }
            ?: sessions.ensureAnonymousUser(device).also { roles.grantRoleIfMissing(it.id, Roles.ANONYMOUS_USER) }
        val expiresAt = if (user.status == "ANONYMOUS") null else Instant.now().plusSeconds(90 * 86_400)
        val session = sessions.saveSession(user.id, device.deviceId, Instant.now(), expiresAt)
        return Principal(user.id, device.deviceId, session.id, user.status == "ANONYMOUS", user.status)
    }

    @Transactional
    override fun updatePushToken(principal: Principal, command: PushTokenCommand) {
        val device = sessions.device(principal.deviceId)
        device.apply(
            Account.of(
                AccountUser(id = principal.userId, status = principal.status),
                device.toAccountDevice(),
            ).updatePushToken(command.apnsToken, command.apnsEnvironment)
        )
    }

    @Transactional
    override fun logout(principal: Principal) {
        val now = Instant.now()
        val session = sessions.findSession(principal.sessionId, principal.userId)
        if (session != null && session.deviceId == principal.deviceId && session.loggedOutAt == null) {
            session.loggedOutAt = now
            session.updatedAt = now
            session.lastSeenAt = now
            sessions.saveSessionState(session)
        }

        val device = sessions.device(principal.deviceId)
        if (device.userId == principal.userId) {
            device.userId = null
            device.updatedAt = now
        }
    }

    @Transactional(readOnly = true)
    override fun loggedInDevices(principal: Principal): LoggedInDevicesResponse {
        val deviceById = devices.findAllByUserId(principal.userId).associateBy { it.deviceId }
        return LoggedInDevicesResponse(
            sessions.activeSessions(principal.userId).mapNotNull { session ->
                val device = deviceById[session.deviceId] ?: return@mapNotNull null
                LoggedInDeviceResponse(
                    deviceId = session.deviceId,
                    platform = device.platform,
                    apnsEnvironment = device.apnsEnvironment,
                    timezone = device.timezone,
                    lastLoginAt = session.lastLoginAt,
                    lastSeenAt = session.lastSeenAt,
                    current = session.id == principal.sessionId,
                )
            }
        )
    }

    @Transactional
    override fun emailLogin(principal: Principal, command: EmailLoginCommand): GoogleLoginResponse {
        val now = Instant.now()
        val normalized = command.email.trim().lowercase()
        var user = users.findByEmailAndProvider(normalized, "EMAIL")
        if (user == null) {
            val verificationCode = command.verificationCode
            if (verificationCode.isNullOrBlank()) {
                throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED, "Email verification code is required.")
            }
            if (!emailCodes.consume(normalized, verificationCode)) {
                throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED, "Invalid or expired email verification code.")
            }
            user = users.save(
                UserEntity(
                    provider = "EMAIL",
                    providerId = normalized,
                    email = normalized,
                    passwordHash = sha256(command.password),
                    status = "ACTIVE",
                    displayName = normalized.substringBefore("@"),
                    avatarColorSeed = "avatar-color-mint",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else if (user.passwordHash != sha256(command.password)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_DEVICE_CREDENTIALS, "Invalid email or password.")
        }
        roles.grantRoleIfMissing(user.id, Roles.REGISTERED_USER)
        val device = sessions.device(principal.deviceId)
        device.apply(Account.of(user.toAccountUser(), device.toAccountDevice()).attachDevice(now))
        val session = sessions.saveSession(user.id, device.deviceId, now, now.plusSeconds(90 * 86_400))
        val token = tokenService.create(user.id, device.deviceId, session.id, false, user.status)
        return GoogleLoginResponse(user.toProfile(), token.first, token.second)
    }

    override fun emailCode(email: String): EmailVerificationCodeResponse {
        val normalized = email.trim().lowercase()
        val ttl = Duration.ofSeconds(properties.email.verificationTtlSeconds)
        val code = "%06d".format(secureRandom.nextInt(1_000_000))
        emailCodes.save(normalized, code, ttl)
        emailSender.send(normalized, code, ttl)
        return EmailVerificationCodeResponse(normalized, ttl.seconds)
    }

    @Transactional
    override fun googleLogin(principal: Principal, idToken: String): GoogleLoginResponse {
        val tokenInfo = googleRest.get()
            .uri { it.path("/tokeninfo").queryParam("id_token", idToken).build() }
            .retrieve()
            .body(Map::class.java)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid Google token.")
        val providerId = tokenInfo["sub"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid Google token.")
        val email = tokenInfo["email"]?.toString() ?: ""
        val name = tokenInfo["name"]?.toString()?.takeIf { it.isNotBlank() } ?: email.substringBefore("@").ifBlank { "Buddy" }
        val now = Instant.now()
        val user = users.findByProviderAndProviderId("GOOGLE", providerId) ?: users.save(
            UserEntity(
                provider = "GOOGLE",
                providerId = providerId,
                email = email,
                status = "ACTIVE",
                displayName = name,
                avatarColorSeed = "avatar-color-mint",
                createdAt = now,
                updatedAt = now,
            )
        )
        roles.grantRoleIfMissing(user.id, Roles.REGISTERED_USER)
        val device = sessions.device(principal.deviceId)
        device.apply(Account.of(user.toAccountUser(), device.toAccountDevice()).attachDevice(now))
        val session = sessions.saveSession(user.id, device.deviceId, now, now.plusSeconds(90 * 86_400))
        val token = tokenService.create(user.id, device.deviceId, session.id, false, user.status)
        return GoogleLoginResponse(user.toProfile(), token.first, token.second)
    }

    private fun UserEntity.toAccountUser() = AccountUser(id = id, status = status)

    private fun DeviceEntity.toAccountDevice() = AccountDevice(deviceId = deviceId, userId = userId)

    private fun DeviceEntity.apply(update: PushTokenUpdate) {
        apnsToken = update.apnsToken
        apnsEnvironment = update.apnsEnvironment
        updatedAt = update.updatedAt
    }

    private fun DeviceEntity.apply(attachment: DeviceAttachment) {
        userId = attachment.userId
        updatedAt = attachment.updatedAt
    }
}
