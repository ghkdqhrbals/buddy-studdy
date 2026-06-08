package com.buddystuddy.backend.auth

import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.auth.application.port.outbound.UserDevicePort
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Date

data class Principal(val userId: Long, val deviceId: String, val sessionId: Long, val anonymous: Boolean)

@Service
class TokenService(private val properties: BuddyStuddyProperties) {
    private val key by lazy {
        val seed = properties.auth.jwtSecret.ifBlank { properties.crypto.masterKey.ifBlank { "dev-buddystuddy-secret" } }
        Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(StandardCharsets.UTF_8)))
    }

    fun create(userId: Long, deviceId: String, sessionId: Long, anonymous: Boolean): Pair<String, Instant> {
        val expiresAt = Instant.now().plusSeconds(properties.auth.accessTokenDays * 86_400)
        val token = Jwts.builder()
            .subject(userId.toString())
            .claim("user_id", userId)
            .claim("device_id", deviceId)
            .claim("session_id", sessionId)
            .claim("is_anonymous", anonymous)
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact()
        return token to expiresAt
    }

    fun parse(raw: String): Principal {
        try {
            val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(raw).payload
            return Principal(
                userId = (claims["user_id"] as Number).toLong(),
                deviceId = claims["device_id"] as String,
                sessionId = (claims["session_id"] as Number).toLong(),
                anonymous = claims["is_anonymous"] as Boolean,
            )
        } catch (error: Exception) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid access token.")
        }
    }
}

@Service
class PrincipalService(
    private val tokenService: TokenService,
    private val devices: DevicePort,
    private val userDevices: UserDevicePort,
) {
    fun authenticate(request: HttpServletRequest): Principal {
        val authorization = request.getHeader("Authorization")
        if (authorization.isNullOrBlank() || !authorization.startsWith("Bearer ")) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED, "Access token is required.")
        }
        val principal = tokenService.parse(authorization.removePrefix("Bearer ").trim())
        val session = userDevices.findByIdAndUserId(principal.sessionId, principal.userId)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Access token principal is no longer valid.")
        if (session.deviceId != principal.deviceId) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_DEVICE_MISMATCH, "Access token device is no longer valid.")
        }
        return principal.copy(anonymous = devices.findByDeviceId(principal.deviceId)?.userId == null || principal.anonymous)
    }

    fun optional(request: HttpServletRequest): Principal? =
        try {
            authenticate(request)
        } catch (_: ApiException) {
            null
        }
}

fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
