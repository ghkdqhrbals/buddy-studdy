package com.buddystuddy.backend.auth

import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Date

data class Principal(val userId: Long, val deviceId: String, val sessionId: Long, val anonymous: Boolean)

@Component
class TokenProvider(private val properties: BuddyStuddyProperties) {
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

    fun validate(raw: String): Boolean =
        runCatching { parseClaims(raw) }.isSuccess

    fun parse(raw: String): Principal {
        try {
            val claims = parseClaims(raw)
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

    private fun parseClaims(raw: String): Claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(raw).payload
}

fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
