package com.buddystuddy.backend.admin.analytics.application.service

import com.buddystuddy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import com.buddystuddy.backend.admin.analytics.application.model.AdminLoginResponse
import com.buddystuddy.backend.admin.analytics.application.model.AdminMetricSeries
import com.buddystuddy.backend.admin.analytics.application.model.AdminMetricsResponse
import com.buddystuddy.backend.admin.analytics.application.port.inbound.AdminAnalyticsAggregationUseCase
import com.buddystuddy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystuddy.backend.admin.analytics.application.port.outbound.AdminAnalyticsMetricPort
import com.buddystuddy.backend.admin.analytics.application.port.outbound.AdminAnalyticsSourcePort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Date

@Service
class AdminAnalyticsService(
    private val properties: BuddyStuddyProperties,
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

    private val key by lazy {
        val seed = properties.auth.jwtSecret.ifBlank { properties.crypto.masterKey.ifBlank { "dev-buddystuddy-admin-secret" } }
        Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(StandardCharsets.UTF_8)))
    }

    override fun login(username: String, password: String): AdminLoginResponse {
        if (properties.admin.password.isBlank()) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.RESOURCE_NOT_FOUND, "Admin login is not configured.")
        }
        if (!constantTimeEquals(username, properties.admin.username) || !constantTimeEquals(password, properties.admin.password)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid admin credentials.")
        }
        val now = Instant.now()
        val expiresAt = now.plusSeconds(properties.admin.tokenHours.coerceAtLeast(1) * 3_600)
        val token = Jwts.builder()
            .subject("admin")
            .claim("admin", true)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact()
        return AdminLoginResponse(token, expiresAt)
    }

    @Transactional
    override fun refresh(adminToken: String, startDate: LocalDate, endDate: LocalDate): AdminMetricsResponse {
        validateAdminToken(adminToken)
        val range = normalizedRange(startDate, endDate)
        refreshDates(range)
        return response(range.first(), range.last(), metrics.findDailyMetrics(range.first(), range.last(), emptySet()))
    }

    @Transactional
    override fun refreshRecent(referenceDate: LocalDate): Int =
        refreshRange(referenceDate.minusDays((properties.analytics.recentDays - 1).coerceAtLeast(0)), referenceDate)

    @Transactional
    override fun refreshCorrection(referenceDate: LocalDate): Int =
        refreshRange(referenceDate.minusDays((properties.analytics.correctionDays - 1).coerceAtLeast(0)), referenceDate)

    @Transactional
    override fun refreshRange(startDate: LocalDate, endDate: LocalDate): Int =
        refreshDates(normalizedRange(startDate, endDate))

    @Transactional(readOnly = true)
    override fun metrics(adminToken: String, startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): AdminMetricsResponse {
        validateAdminToken(adminToken)
        val range = normalizedRange(startDate, endDate)
        return response(range.first(), range.last(), metrics.findDailyMetrics(range.first(), range.last(), metricKeys))
    }

    private fun validateAdminToken(adminToken: String) {
        try {
            val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(adminToken).payload
            if (claims.subject != "admin" || claims["admin"] != true) {
                throw IllegalArgumentException("not an admin token")
            }
        } catch (error: Exception) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid admin token.")
        }
    }

    private fun normalizedRange(startDate: LocalDate, endDate: LocalDate): List<LocalDate> {
        val start = minOf(startDate, endDate)
        val end = maxOf(startDate, endDate)
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .take(370)
            .toList()
    }

    private fun refreshDates(range: List<LocalDate>): Int {
        if (range.isEmpty()) {
            return 0
        }
        val rows = range.flatMap { source.collectDailyMetrics(it) }
        metrics.upsertDailyMetrics(rows)
        return rows.size
    }

    private fun response(startDate: LocalDate, endDate: LocalDate, rows: List<AdminDailyMetricPoint>): AdminMetricsResponse {
        val series = rows
            .filter { it.metricKey in exposedMetricKeys }
            .sortedWith(compareBy<AdminDailyMetricPoint> { it.metricKey }.thenBy { it.dimension ?: "" }.thenBy { it.date })
            .groupBy { it.metricKey to it.dimension }
            .map { (key, points) ->
                AdminMetricSeries(metricKey = key.first, dimension = key.second, points = points)
            }
        return AdminMetricsResponse(startDate, endDate, series)
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
        val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(leftBytes, rightBytes)
    }
}
