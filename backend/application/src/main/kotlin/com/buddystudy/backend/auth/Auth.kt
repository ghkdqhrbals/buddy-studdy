package com.buddystudy.backend.auth

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.security.JwtSupport
import com.buddystudy.backend.config.BuddyStudyProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

data class Principal(
    val userId: Long,
    val deviceId: String,
    val sessionId: Long,
    val anonymous: Boolean,
    val status: String = if (anonymous) "ANONYMOUS" else "ACTIVE",
)

@Component
class TokenProvider(private val properties: BuddyStudyProperties) {
    private val secret by lazy {
        val seed = properties.auth.jwtSecret.ifBlank { properties.crypto.masterKey.ifBlank { "dev-buddystudy-secret" } }
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(StandardCharsets.UTF_8))
    }

    fun create(userId: Long, deviceId: String, sessionId: Long, anonymous: Boolean, status: String): Pair<String, Instant> {
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plusSeconds(properties.auth.accessTokenDays * 86_400)
        val token = JwtSupport.hs256(
            payload = linkedMapOf(
                "sub" to userId.toString(),
                "user_id" to userId,
                "device_id" to deviceId,
                "status" to status,
                "session_id" to sessionId,
                "is_anonymous" to anonymous,
                "iat" to issuedAt.epochSecond,
                "exp" to expiresAt.epochSecond,
            ),
            secret = secret,
        )
        return token to expiresAt
    }

    fun validate(raw: String): Boolean =
        runCatching { parseClaims(raw) }.isSuccess

    fun parse(raw: String): Principal {
        try {
            val claims = parseClaims(raw)
            return Principal(
                userId = claims.number("user_id").toLong(),
                deviceId = claims["device_id"] as String,
                sessionId = claims.number("session_id").toLong(),
                anonymous = claims["is_anonymous"] as Boolean,
                status = claims["status"] as? String ?: "ACTIVE",
            )
        } catch (error: Exception) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid access token.")
        }
    }

    private fun parseClaims(raw: String): Map<String, Any?> {
        val claims = JwtSupport.verifyHs256(raw, secret)
        val expiresAt = claims.number("exp").toLong()
        require(Instant.ofEpochSecond(expiresAt).isAfter(Instant.now())) { "Access token has expired." }
        return claims
    }

    private fun Map<String, Any?>.number(name: String): Number =
        this[name] as? Number ?: error("JWT claim '$name' must be numeric.")
}

fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
