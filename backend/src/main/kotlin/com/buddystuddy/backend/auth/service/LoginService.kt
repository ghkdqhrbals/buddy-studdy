package com.buddystuddy.backend.auth.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.TokenService
import com.buddystuddy.backend.auth.sha256
import com.buddystuddy.backend.auth.repository.DeviceRepository
import com.buddystuddy.backend.auth.repository.UserRepository
import com.buddystuddy.backend.common.api.ApiErrorCode
import com.buddystuddy.backend.common.api.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.common.service.BackendSupportService
import com.buddystuddy.backend.domain.DeviceEntity
import com.buddystuddy.backend.domain.UserEntity
import com.buddystuddy.backend.dto.AccessTokenResponse
import com.buddystuddy.backend.dto.DeviceRegisterRequest
import com.buddystuddy.backend.dto.DeviceRegisterResponse
import com.buddystuddy.backend.dto.EmailLoginRequest
import com.buddystuddy.backend.dto.EmailVerificationCodeResponse
import com.buddystuddy.backend.dto.GoogleLoginResponse
import com.buddystuddy.backend.dto.PushTokenRequest
import com.buddystuddy.backend.dto.toProfile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import java.time.Instant

@Service
class LoginService(
    private val properties: BuddyStuddyProperties,
    private val users: UserRepository,
    private val devices: DeviceRepository,
    private val tokenService: TokenService,
    private val support: BackendSupportService,
) {
    private val googleRest = RestClient.builder().baseUrl("https://oauth2.googleapis.com").build()

    @Transactional
    fun register(payload: DeviceRegisterRequest): DeviceRegisterResponse {
        val deviceId = support.randomToken("dev")
        val secret = support.randomToken("sec")
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
                apnsToken = payload.apnsToken,
                platform = payload.platform,
                apnsEnvironment = payload.apnsEnvironment,
                language = payload.language,
                timezone = payload.timezone,
                createdAt = now,
                updatedAt = now,
                lastSeenAt = now,
            )
        )
        val session = support.saveSession(user.id, device.deviceId, now, null)
        val token = tokenService.create(user.id, device.deviceId, session.id, true)
        return DeviceRegisterResponse(device.deviceId, secret, token.first, token.second)
    }

    @Transactional
    fun token(deviceId: String, clientSecret: String): AccessTokenResponse {
        val principal = authenticateDevice(deviceId, clientSecret)
        val token = tokenService.create(principal.userId, principal.deviceId, principal.sessionId, principal.anonymous)
        return AccessTokenResponse(token.first, token.second)
    }

    @Transactional
    fun authenticateDevice(deviceId: String, clientSecret: String): Principal {
        val device = support.device(deviceId)
        if (device.clientSecretHash != sha256(clientSecret)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_DEVICE_CREDENTIALS, "Invalid device credentials.")
        }
        val userId = device.userId ?: support.ensureAnonymousUser(device).id
        val user = users.findById(userId).orElseThrow()
        val expiresAt = if (user.status == "ANONYMOUS") null else Instant.now().plusSeconds(90 * 86_400)
        val session = support.saveSession(user.id, device.deviceId, Instant.now(), expiresAt)
        return Principal(user.id, device.deviceId, session.id, user.status == "ANONYMOUS")
    }

    @Transactional
    fun updatePushToken(principal: Principal, payload: PushTokenRequest) {
        val device = support.device(principal.deviceId)
        device.apnsToken = payload.apnsToken
        device.apnsEnvironment = payload.apnsEnvironment
        device.updatedAt = Instant.now()
    }

    @Transactional
    fun emailLogin(principal: Principal, payload: EmailLoginRequest): GoogleLoginResponse {
        val now = Instant.now()
        val normalized = payload.email.trim().lowercase()
        var user = users.findByEmailAndProvider(normalized, "EMAIL")
        if (user == null) {
            if (payload.verificationCode.isNullOrBlank()) {
                throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.AUTH_GOOGLE_REQUIRED, "Email verification code is required.")
            }
            user = users.save(
                UserEntity(
                    provider = "EMAIL",
                    providerId = normalized,
                    email = normalized,
                    passwordHash = sha256(payload.password),
                    status = "ACTIVE",
                    displayName = normalized.substringBefore("@"),
                    avatarColorSeed = "avatar-color-mint",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else if (user.passwordHash != sha256(payload.password)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_DEVICE_CREDENTIALS, "Invalid email or password.")
        }
        val device = support.device(principal.deviceId)
        device.userId = user.id
        device.updatedAt = now
        val session = support.saveSession(user.id, device.deviceId, now, now.plusSeconds(90 * 86_400))
        val token = tokenService.create(user.id, device.deviceId, session.id, false)
        return GoogleLoginResponse(user.toProfile(), token.first, token.second)
    }

    fun emailCode(email: String) = EmailVerificationCodeResponse(email.trim().lowercase(), properties.email.verificationTtlSeconds)

    @Transactional
    fun googleLogin(principal: Principal, idToken: String): GoogleLoginResponse {
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
        val device = support.device(principal.deviceId)
        device.userId = user.id
        device.updatedAt = now
        val session = support.saveSession(user.id, device.deviceId, now, now.plusSeconds(90 * 86_400))
        val token = tokenService.create(user.id, device.deviceId, session.id, false)
        return GoogleLoginResponse(user.toProfile(), token.first, token.second)
    }
}
