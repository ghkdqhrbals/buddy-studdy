package com.buddystuddy.backend.auth.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.TokenProvider
import com.buddystuddy.backend.auth.sha256
import com.buddystuddy.backend.auth.domain.AccountAggregate
import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.auth.application.port.inbound.IssueDeviceTokenUseCase
import com.buddystuddy.backend.auth.application.port.inbound.LoginUseCase
import com.buddystuddy.backend.auth.application.port.inbound.RegisterDeviceUseCase
import com.buddystuddy.backend.auth.application.port.inbound.UpdatePushTokenUseCase
import com.buddystuddy.backend.auth.application.port.inbound.EmailLoginCommand
import com.buddystuddy.backend.auth.application.port.inbound.RegisterDeviceCommand
import com.buddystuddy.backend.auth.application.port.inbound.PushTokenCommand
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.domain.DeviceEntity
import com.buddystuddy.backend.domain.UserEntity
import com.buddystuddy.backend.auth.application.model.AccessTokenResponse
import com.buddystuddy.backend.auth.application.model.DeviceRegisterResponse
import com.buddystuddy.backend.auth.application.model.EmailVerificationCodeResponse
import com.buddystuddy.backend.auth.application.model.GoogleLoginResponse
import com.buddystuddy.backend.profile.application.model.toProfile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import java.time.Instant

@Service
class LoginService(
    private val properties: BuddyStuddyProperties,
    private val users: UserPort,
    private val devices: DevicePort,
    private val tokenService: TokenProvider,
    private val sessions: AccountSessionManager,
    private val tokens: RandomTokenGenerator,
) : RegisterDeviceUseCase, IssueDeviceTokenUseCase, LoginUseCase, UpdatePushTokenUseCase {
    private val googleRest = RestClient.builder().baseUrl("https://oauth2.googleapis.com").build()

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
        val session = sessions.saveSession(user.id, device.deviceId, now, null)
        val token = tokenService.create(user.id, device.deviceId, session.id, true)
        return DeviceRegisterResponse(device.deviceId, secret, token.first, token.second)
    }

    @Transactional
    override fun token(deviceId: String, clientSecret: String): AccessTokenResponse {
        val principal = authenticateDevice(deviceId, clientSecret)
        val token = tokenService.create(principal.userId, principal.deviceId, principal.sessionId, principal.anonymous)
        return AccessTokenResponse(token.first, token.second)
    }

    @Transactional
    override fun authenticateDevice(deviceId: String, clientSecret: String): Principal {
        val device = sessions.device(deviceId)
        if (device.clientSecretHash != sha256(clientSecret)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_DEVICE_CREDENTIALS, "Invalid device credentials.")
        }
        val userId = device.userId ?: sessions.ensureAnonymousUser(device).id
        val user = users.findById(userId).orElseThrow()
        val expiresAt = if (user.status == "ANONYMOUS") null else Instant.now().plusSeconds(90 * 86_400)
        val session = sessions.saveSession(user.id, device.deviceId, Instant.now(), expiresAt)
        return Principal(user.id, device.deviceId, session.id, user.status == "ANONYMOUS")
    }

    @Transactional
    override fun updatePushToken(principal: Principal, command: PushTokenCommand) {
        val device = sessions.device(principal.deviceId)
        val user = users.findById(principal.userId).orElseThrow()
        AccountAggregate.of(user, device).updatePushToken(command.apnsToken, command.apnsEnvironment)
    }

    @Transactional
    override fun emailLogin(principal: Principal, command: EmailLoginCommand): GoogleLoginResponse {
        val now = Instant.now()
        val normalized = command.email.trim().lowercase()
        var user = users.findByEmailAndProvider(normalized, "EMAIL")
        if (user == null) {
            if (command.verificationCode.isNullOrBlank()) {
                throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.AUTH_GOOGLE_REQUIRED, "Email verification code is required.")
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
        val device = sessions.device(principal.deviceId)
        AccountAggregate.of(user, device).attachDevice(now)
        val session = sessions.saveSession(user.id, device.deviceId, now, now.plusSeconds(90 * 86_400))
        val token = tokenService.create(user.id, device.deviceId, session.id, false)
        return GoogleLoginResponse(user.toProfile(), token.first, token.second)
    }

    override fun emailCode(email: String) = EmailVerificationCodeResponse(email.trim().lowercase(), properties.email.verificationTtlSeconds)

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
        val device = sessions.device(principal.deviceId)
        AccountAggregate.of(user, device).attachDevice(now)
        val session = sessions.saveSession(user.id, device.deviceId, now, now.plusSeconds(90 * 86_400))
        val token = tokenService.create(user.id, device.deviceId, session.id, false)
        return GoogleLoginResponse(user.toProfile(), token.first, token.second)
    }
}
