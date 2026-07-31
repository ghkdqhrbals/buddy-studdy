package com.buddystudy.backend.admin.analytics.application.service

import com.buddystudy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import com.buddystudy.backend.admin.analytics.application.model.AdminLoginResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminMetricSeries
import com.buddystudy.backend.admin.analytics.application.model.AdminMetricsResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorPageResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorSummary
import com.buddystudy.backend.admin.analytics.application.model.AdminSessionResponse
import com.buddystudy.backend.admin.analytics.application.model.CreateAdminOperatorCommand
import com.buddystudy.backend.admin.analytics.application.model.UpdateAdminOperatorCommand
import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsAggregationUseCase
import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminAnalyticsMetricPort
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminOperatorPort
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
    private val operators: AdminOperatorPort,
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
        val now = Instant.now()
        val normalizedUsername = username.trim().lowercase()
        var principal = operators.authenticate(normalizedUsername, password, now)
        if (principal == null && operators.activeStatus(normalizedUsername) == null) {
            if (properties.admin.password.isBlank()) {
                throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.RESOURCE_NOT_FOUND, "Admin login is not configured.")
            }
            if (
                constantTimeEquals(normalizedUsername, properties.admin.username.trim().lowercase()) &&
                constantTimeEquals(password, properties.admin.password)
            ) {
                principal = operators.ensureBootstrap(
                    username = normalizedUsername,
                    displayName = "Primary administrator",
                    password = password,
                )
            }
        }
        if (principal == null || principal.status != "ACTIVE") {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid admin credentials.")
        }
        val expiresAt = now.plusSeconds(properties.admin.tokenHours.coerceAtLeast(1) * 3_600)
        val token = createAdminToken(principal.username, now, expiresAt)
        return AdminLoginResponse(token, expiresAt, principal.username)
    }

    override suspend fun session(adminToken: String): AdminSessionResponse =
        AdminSessionResponse(authenticatedUsername(adminToken))

    override suspend fun operators(
        adminToken: String,
        query: String?,
        limit: Int,
        offset: Int,
    ): AdminOperatorPageResponse {
        authenticatedUsername(adminToken)
        return operators.operators(query?.trim()?.takeIf { it.isNotEmpty() }, limit.coerceIn(1, 100), offset.coerceAtLeast(0))
    }

    override suspend fun createOperator(
        adminToken: String,
        command: CreateAdminOperatorCommand,
    ): AdminOperatorSummary {
        val actor = authenticatedUsername(adminToken)
        val normalized = command.copy(
            username = validatedUsername(command.username),
            displayName = validatedDisplayName(command.displayName),
            password = validatedPassword(command.password),
        )
        return operators.create(normalized, actor)
            ?: throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "Administrator username is already in use.")
    }

    override suspend fun updateOperator(
        adminToken: String,
        operatorId: Long,
        command: UpdateAdminOperatorCommand,
    ): AdminOperatorSummary {
        val actor = authenticatedUsername(adminToken)
        val normalizedStatus = command.status?.trim()?.uppercase()?.also {
            if (it !in setOf("ACTIVE", "DISABLED")) {
                throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Invalid administrator status.")
            }
        }
        val normalized = UpdateAdminOperatorCommand(
            displayName = command.displayName?.let(::validatedDisplayName),
            status = normalizedStatus,
            password = command.password?.takeIf { it.isNotBlank() }?.let(::validatedPassword),
        )
        return try {
            operators.update(operatorId, normalized, actor)
                ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Administrator account was not found.")
        } catch (error: IllegalArgumentException) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, error.message.orEmpty())
        }
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
        authenticatedUsername(adminToken)
    }

    private suspend fun createAdminToken(username: String, now: Instant, expiresAt: Instant): String {
        val header = mapOf("alg" to "HS256", "typ" to "JWT")
        val payload = mapOf(
            "sub" to "admin",
            "admin" to true,
            "admin_username" to username,
            "iat" to now.epochSecond,
            "exp" to expiresAt.epochSecond,
        )
        val signingInput = "${base64Url(jwtJson.writeValueAsBytes(header))}.${base64Url(jwtJson.writeValueAsBytes(payload))}"
        return "$signingInput.${base64Url(hmacSha256(signingInput))}"
    }

    private suspend fun authenticatedUsername(adminToken: String): String {
        try {
            val username = validateAdminToken(adminToken)
            val active = operators.activeStatus(username)
            if (active == false || (active == null && username != properties.admin.username.trim().lowercase())) {
                error("inactive admin")
            }
            return username
        } catch (error: Exception) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid admin token.")
        }
    }

    private suspend fun validateAdminToken(adminToken: String): String {
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
        return (claims["admin_username"] as? String)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: properties.admin.username.trim().lowercase()
    }

    private fun validatedUsername(value: String): String {
        val normalized = value.trim().lowercase()
        if (!normalized.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}"))) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Administrator username must be 3-64 lowercase letters, numbers, dots, underscores, or hyphens.")
        }
        return normalized
    }

    private fun validatedDisplayName(value: String): String =
        value.trim().takeIf { it.length in 2..100 }
            ?: throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Administrator display name must be 2-100 characters.")

    private fun validatedPassword(value: String): String =
        value.takeIf { it.length in 12..128 }
            ?: throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Administrator password must be 12-128 characters.")

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
