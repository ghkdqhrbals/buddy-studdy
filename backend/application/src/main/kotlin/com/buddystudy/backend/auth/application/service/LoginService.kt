package com.buddystudy.backend.auth.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.auth.sha256
import com.buddystudy.auth.domain.Account
import com.buddystudy.auth.domain.AccountDevice
import com.buddystudy.auth.domain.AccountUser
import com.buddystudy.auth.domain.PushTokenUpdate
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.auth.domain.entity.ApnsEnvironment
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.AppleIdentityPort
import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationCodePort
import com.buddystudy.backend.auth.application.port.outbound.EmailVerificationSenderPort
import com.buddystudy.backend.auth.application.port.outbound.GoogleIdentityPort
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.auth.application.port.inbound.IssueDeviceTokenUseCase
import com.buddystudy.backend.auth.application.port.inbound.LoginUseCase
import com.buddystudy.backend.auth.application.port.inbound.RegisterDeviceUseCase
import com.buddystudy.backend.auth.application.port.inbound.UpdatePushTokenUseCase
import com.buddystudy.backend.auth.application.port.inbound.EmailLoginCommand
import com.buddystudy.backend.auth.application.port.inbound.RegisterDeviceCommand
import com.buddystudy.backend.auth.application.port.inbound.PushTokenCommand
import com.buddystudy.backend.auth.application.permission.Roles
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.auth.application.model.AccessTokenResponse
import com.buddystudy.backend.auth.application.model.DeviceRegisterResponse
import com.buddystudy.backend.auth.application.model.EmailVerificationCodeResponse
import com.buddystudy.backend.auth.application.model.GoogleLoginResponse
import com.buddystudy.backend.auth.application.model.LoggedInDeviceResponse
import com.buddystudy.backend.auth.application.model.LoggedInDevicesResponse
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.Duration
import java.security.SecureRandom

@Service
class LoginService(
    private val properties: BuddyStudyProperties,
    private val users: UserPort,
    private val devices: DevicePort,
    private val tokenService: TokenProvider,
    private val sessions: AccountSessionManager,
    private val emailCodes: EmailVerificationCodePort,
    private val emailSender: EmailVerificationSenderPort,
    private val roles: RoleAssignmentPort,
    private val appleIdentities: AppleIdentityPort,
    private val googleIdentities: GoogleIdentityPort,
    private val authenticatedLogins: AuthenticatedLoginManager,
    private val deviceRegistrations: DeviceRegistrationManager,
) : RegisterDeviceUseCase, IssueDeviceTokenUseCase, LoginUseCase, UpdatePushTokenUseCase {
    private val secureRandom = SecureRandom()

    override suspend fun register(command: RegisterDeviceCommand): DeviceRegisterResponse {
        val installationId = command.installationId.trim()
        if (installationId.isNotEmpty() && installationId.length !in 32..256) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Installation identifier must be between 32 and 256 characters.",
            )
        }
        val installationKeyHash = installationId.takeIf(String::isNotEmpty)?.let(::sha256)
        return try {
            deviceRegistrations.register(command, installationKeyHash)
        } catch (duplicate: DuplicateKeyException) {
            if (installationKeyHash == null) {
                throw duplicate
            }
            deviceRegistrations.register(command, installationKeyHash)
        }
    }

    @Transactional
    override suspend fun token(deviceId: String, clientSecret: String): AccessTokenResponse {
        val principal = authenticateDevice(deviceId, clientSecret)
        val token = tokenService.create(principal.userId, principal.deviceId, principal.sessionId, principal.anonymous, principal.status)
        return AccessTokenResponse(token.first, token.second)
    }

    @Transactional
    override suspend fun authenticateDevice(deviceId: String, clientSecret: String): Principal {
        val device = sessions.device(deviceId)
        if (device.clientSecretHash != sha256(clientSecret)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_DEVICE_CREDENTIALS, "Invalid device credentials.")
        }
        val user = device.userId
            ?.let { users.findById(it) ?: error("Authenticated user not found: $it") }
            ?: sessions.ensureAnonymousUser(device).also { roles.grantRoleIfMissing(it.id, Roles.ANONYMOUS_USER) }
        val expiresAt = if (user.status == UserStatus.ANONYMOUS) null else Instant.now().plusSeconds(90 * 86_400)
        val session = sessions.saveSession(user.id, device.deviceId, Instant.now(), expiresAt)
        return Principal(user.id, device.deviceId, session.id, user.status == UserStatus.ANONYMOUS, user.status.name)
    }

    @Transactional
    override suspend fun updatePushToken(principal: Principal, command: PushTokenCommand) {
        val device = sessions.device(principal.deviceId)
        device.apply(
            Account.of(
                AccountUser(id = principal.userId, status = principal.status),
                device.toAccountDevice(),
            ).updatePushToken(command.apnsToken, command.apnsEnvironment)
        )
        devices.save(device)
        clearOtherPushTokens(principal.userId, principal.deviceId, device.updatedAt)
    }

    @Transactional
    override suspend fun logout(principal: Principal) {
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
            devices.save(device)
        }
    }

    @Transactional(readOnly = true)
    override suspend fun loggedInDevices(principal: Principal): LoggedInDevicesResponse {
        val deviceById = devices.findAllByUserId(principal.userId).associateBy { it.deviceId }
        return LoggedInDevicesResponse(
            sessions.activeSessions(principal.userId).mapNotNull { session ->
                val device = deviceById[session.deviceId] ?: return@mapNotNull null
                LoggedInDeviceResponse(
                    deviceId = session.deviceId,
                    platform = device.platform.databaseValue,
                    apnsEnvironment = device.apnsEnvironment.databaseValue,
                    timezone = device.timezone,
                    lastLoginAt = session.lastLoginAt,
                    lastSeenAt = session.lastSeenAt,
                    current = session.id == principal.sessionId,
                )
            }
        )
    }

    override suspend fun emailLogin(principal: Principal, command: EmailLoginCommand): GoogleLoginResponse {
        val normalized = command.email.trim().lowercase()
        val passwordHash = sha256(command.password)
        val existingUser = users.findByEmailAndProvider(normalized, "EMAIL")
        if (existingUser == null) {
            val verificationCode = command.verificationCode
            if (verificationCode.isNullOrBlank()) {
                throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED, "Email verification code is required.")
            }
            if (!emailCodes.consume(normalized, verificationCode)) {
                throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED, "Invalid or expired email verification code.")
            }
        } else if (existingUser.passwordHash != passwordHash) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_EMAIL_CREDENTIALS, "Invalid email or password.")
        }
        return authenticatedLogins.attachEmailIdentity(principal, normalized, passwordHash, Instant.now())
    }

    override suspend fun emailCode(email: String): EmailVerificationCodeResponse {
        val normalized = email.trim().lowercase()
        val ttl = Duration.ofSeconds(properties.email.verificationTtlSeconds)
        val code = "%06d".format(secureRandom.nextInt(1_000_000))
        emailCodes.save(normalized, code, ttl)
        emailSender.send(normalized, code, ttl)
        return EmailVerificationCodeResponse(normalized, ttl.seconds)
    }

    override suspend fun googleLogin(principal: Principal, idToken: String): GoogleLoginResponse {
        val identity = googleIdentities.verify(idToken)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid Google token.")
        return authenticatedLogins.attachGoogleIdentity(principal, identity, Instant.now())
    }

    override suspend fun appleLogin(principal: Principal, idToken: String): GoogleLoginResponse {
        val identity = appleIdentities.verify(idToken)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid Apple token.")
        return authenticatedLogins.attachAppleIdentity(principal, identity, Instant.now())
    }

    private suspend fun UserEntity.toAccountUser() = AccountUser(id = id, status = status.name)

    private suspend fun DeviceEntity.toAccountDevice() = AccountDevice(deviceId = deviceId, userId = userId)

    private suspend fun DeviceEntity.apply(update: PushTokenUpdate) {
        apnsToken = update.apnsToken
        apnsEnvironment = ApnsEnvironment.fromDatabaseValue(update.apnsEnvironment.lowercase())
        updatedAt = update.updatedAt
    }

    private suspend fun clearOtherPushTokens(userId: Long, currentDeviceId: String, now: Instant) {
        devices.findAllByUserId(userId)
            .filter { it.deviceId != currentDeviceId && it.apnsToken.isNotBlank() }
            .forEach { device ->
                device.apnsToken = ""
                device.updatedAt = now
                devices.save(device)
            }
    }

}
