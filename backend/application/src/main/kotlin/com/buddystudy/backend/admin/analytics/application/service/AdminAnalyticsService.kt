package com.buddystudy.backend.admin.analytics.application.service

import com.buddystudy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import com.buddystudy.backend.admin.analytics.application.model.AdminLoginResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminMetricSeries
import com.buddystudy.backend.admin.analytics.application.model.AdminMetricsResponse
import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsAggregationUseCase
import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminAnalyticsMetricPort
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminAnalyticsSourcePort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class AdminAnalyticsService(
    private val properties: BuddyStudyProperties,
    private val metrics: AdminAnalyticsMetricPort,
    private val source: AdminAnalyticsSourcePort,
) : AdminAnalyticsUseCase, AdminAnalyticsAggregationUseCase {
    private val exposedMetricKeys = setOf(
        "daily_active_users",
        "weekly_active_learners",
        "question_created_count",
        "answer_submitted_count",
        "answer_rate",
        "push_open_rate",
        "question_to_answer_latency",
        "study_streak",
        "quota_used_count",
    )

    private val jwtJson = JsonMapperProvider.mapper
    private val keyBytes by lazy {
        val seed = properties.auth.jwtSecret.ifBlank { properties.crypto.masterKey.ifBlank { "dev-buddystudy-admin-secret" } }
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun login(username: String, password: String): AdminLoginResponse {
        if (properties.admin.password.isBlank()) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.RESOURCE_NOT_FOUND, "Admin login is not configured.")
        }
        if (!constantTimeEquals(username, properties.admin.username) || !constantTimeEquals(password, properties.admin.password)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid admin credentials.")
        }
        val now = Instant.now()
        val expiresAt = now.plusSeconds(properties.admin.tokenHours.coerceAtLeast(1) * 3_600)
        val token = createAdminToken(now, expiresAt)
        return AdminLoginResponse(token, expiresAt)
    }

    @Transactional
    override suspend fun refresh(adminToken: String, startDate: LocalDate, endDate: LocalDate): AdminMetricsResponse {
        validate(adminToken)
        val range = normalizedRange(startDate, endDate)
        refreshDates(range)
        return response(range.first(), range.last(), metrics.findDailyMetrics(range.first(), range.last(), emptySet()))
    }

    @Transactional
    override suspend fun refreshRecent(referenceDate: LocalDate): Int =
        refreshRange(referenceDate.minusDays((properties.analytics.recentDays - 1).coerceAtLeast(0)), referenceDate)

    @Transactional
    override suspend fun refreshCorrection(referenceDate: LocalDate): Int =
        refreshRange(referenceDate.minusDays((properties.analytics.correctionDays - 1).coerceAtLeast(0)), referenceDate)

    @Transactional
    override suspend fun refreshRange(startDate: LocalDate, endDate: LocalDate): Int =
        refreshDates(normalizedRange(startDate, endDate))

    @Transactional(readOnly = true)
    override suspend fun metrics(adminToken: String, startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): AdminMetricsResponse {
        validate(adminToken)
        val range = normalizedRange(startDate, endDate)
        return response(range.first(), range.last(), metrics.findDailyMetrics(range.first(), range.last(), metricKeys))
    }

    override suspend fun validate(adminToken: String) {
        try {
            validateAdminToken(adminToken)
        } catch (error: Exception) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid admin token.")
        }
    }

    private suspend fun createAdminToken(now: Instant, expiresAt: Instant): String {
        val header = mapOf("alg" to "HS256", "typ" to "JWT")
        val payload = mapOf(
            "sub" to "admin",
            "admin" to true,
            "iat" to now.epochSecond,
            "exp" to expiresAt.epochSecond,
        )
        val signingInput = "${base64Url(jwtJson.writeValueAsBytes(header))}.${base64Url(jwtJson.writeValueAsBytes(payload))}"
        return "$signingInput.${base64Url(hmacSha256(signingInput))}"
    }

    private suspend fun validateAdminToken(adminToken: String) {
        val parts = adminToken.split(".")
        require(parts.size == 3) { "invalid token parts" }
        val signingInput = "${parts[0]}.${parts[1]}"
        val expected = base64Url(hmacSha256(signingInput))
        require(MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), parts[2].toByteArray(StandardCharsets.UTF_8))) {
            "invalid signature"
        }
        val claims = jwtJson.readValue<Map<String, Any?>>(Base64.getUrlDecoder().decode(parts[1]))
        require(claims["sub"] == "admin" && claims["admin"] == true) { "not an admin token" }
        val exp = (claims["exp"] as? Number)?.toLong() ?: error("missing exp")
        require(Instant.now().epochSecond < exp) { "expired admin token" }
    }

    private suspend fun hmacSha256(value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    private suspend fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private suspend fun normalizedRange(startDate: LocalDate, endDate: LocalDate): List<LocalDate> {
        val start = minOf(startDate, endDate)
        val end = maxOf(startDate, endDate)
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .take(370)
            .toList()
    }

    private suspend fun refreshDates(range: List<LocalDate>): Int {
        if (range.isEmpty()) {
            return 0
        }
        val rows = range.flatMap { source.collectDailyMetrics(it) }
        metrics.upsertDailyMetrics(rows)
        return rows.size
    }

    private suspend fun response(startDate: LocalDate, endDate: LocalDate, rows: List<AdminDailyMetricPoint>): AdminMetricsResponse {
        val series = rows
            .filter { it.metricKey in exposedMetricKeys }
            .sortedWith(compareBy<AdminDailyMetricPoint> { it.metricKey }.thenBy { it.dimension ?: "" }.thenBy { it.date })
            .groupBy { it.metricKey to it.dimension }
            .map { (key, points) ->
                AdminMetricSeries(metricKey = key.first, dimension = key.second, points = points)
            }
        return AdminMetricsResponse(startDate, endDate, series)
    }

    private suspend fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
        val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(leftBytes, rightBytes)
    }
}
