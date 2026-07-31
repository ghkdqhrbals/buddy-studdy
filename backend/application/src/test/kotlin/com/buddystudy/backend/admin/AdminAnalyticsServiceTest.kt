package com.buddystudy.backend.admin

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminAnalyticsMetricPort
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminOperatorPort
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminAnalyticsSourcePort
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorPageResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorPrincipal
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorSummary
import com.buddystudy.backend.admin.analytics.application.model.CreateAdminOperatorCommand
import com.buddystudy.backend.admin.analytics.application.model.UpdateAdminOperatorCommand
import com.buddystudy.backend.admin.analytics.application.service.AdminAnalyticsService
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.Instant

class AdminAnalyticsServiceTest {
    private val properties = BuddyStudyProperties().apply {
        crypto.masterKey = "test-master-key"
        admin.username = "admin"
        admin.password = "secret"
    }
    private val metrics = FakeMetricPort()
    private val source = FakeSourcePort()
    private val operators = FakeOperatorPort()
    private val service = AdminAnalyticsService(properties, metrics, source, operators)

    @Test
    fun `default admin credentials are admin admin`(): Unit = runBlocking {
        val defaultService = AdminAnalyticsService(
            BuddyStudyProperties().apply { crypto.masterKey = "test-master-key" },
            FakeMetricPort(),
            FakeSourcePort(),
            FakeOperatorPort(),
        )

        val token = defaultService.login("admin", "admin").adminToken

        assertThat(token).isNotBlank()
    }

    @Test
    fun `admin login returns token and metrics require valid admin token`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.metrics("bad-token", LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-01"), emptySet()) } }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN)

        val token = service.login("admin", "secret").adminToken
        val response = service.metrics(token, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-01"), emptySet())

        assertThat(response.series).isEmpty()
    }

    @Test
    fun `admin token validation rejects tampered token`(): Unit = runBlocking {
        val token = service.login("admin", "secret").adminToken
        val tampered = token.substringBeforeLast(".") + ".tampered"

        assertThatThrownBy { runBlocking { service.validate(tampered) } }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN)
    }

    @Test
    fun `legacy administrator is persisted and additional operators can be managed`(): Unit = runBlocking {
        val login = service.login("admin", "secret")
        val created = service.createOperator(
            login.adminToken,
            CreateAdminOperatorCommand("operator.two", "Second operator", "secure-password-123"),
        )

        assertThat(login.username).isEqualTo("admin")
        assertThat(created.username).isEqualTo("operator.two")
        assertThat(service.operators(login.adminToken, null, 20, 0).operators)
            .extracting<String> { it.username }
            .containsExactly("admin", "operator.two")

        service.updateOperator(
            login.adminToken,
            created.id,
            UpdateAdminOperatorCommand(status = "DISABLED", displayName = null, password = null),
        )

        assertThatThrownBy { runBlocking { service.login("operator.two", "secure-password-123") } }
            .isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `refresh stores daily source metrics and returns time series`(): Unit = runBlocking {
        val day = LocalDate.parse("2026-06-01")
        source.rows[day] = listOf(
            AdminDailyMetricPoint(day, "daily_active_users", null, 7.0),
            AdminDailyMetricPoint(day, "answer_rate", null, 0.75, sampleCount = 4),
        )
        val token = service.login("admin", "secret").adminToken

        service.refresh(token, day, day)
        val response = service.metrics(token, day, day, emptySet())

        assertThat(metrics.upserted).hasSize(2)
        assertThat(response.series.map { it.metricKey }).containsExactlyInAnyOrder("daily_active_users", "answer_rate")
        assertThat(response.series.single { it.metricKey == "daily_active_users" }.points.single().value).isEqualTo(7.0)
        assertThat(response.series.single { it.metricKey == "answer_rate" }.points.single().sampleCount).isEqualTo(4)
    }

    @Test
    fun `scheduled recent refresh recomputes configured recent date range`(): Unit = runBlocking {
        properties.analytics.recentDays = 2
        val yesterday = LocalDate.parse("2026-06-24")
        val today = LocalDate.parse("2026-06-25")
        source.rows[yesterday] = listOf(AdminDailyMetricPoint(yesterday, "daily_active_users", null, 3.0))
        source.rows[today] = listOf(AdminDailyMetricPoint(today, "daily_active_users", null, 5.0))

        val rows = service.refreshRecent(today)

        assertThat(rows).isEqualTo(2)
        assertThat(metrics.upserted.map { it.date }).containsExactly(yesterday, today)
    }

    @Test
    fun `scheduled correction refresh recomputes configured correction date range`(): Unit = runBlocking {
        properties.analytics.correctionDays = 3
        val first = LocalDate.parse("2026-06-23")
        val second = LocalDate.parse("2026-06-24")
        val third = LocalDate.parse("2026-06-25")
        source.rows[first] = listOf(AdminDailyMetricPoint(first, "question_created_count", null, 1.0))
        source.rows[second] = listOf(AdminDailyMetricPoint(second, "question_created_count", null, 2.0))
        source.rows[third] = listOf(AdminDailyMetricPoint(third, "question_created_count", null, 3.0))

        val rows = service.refreshCorrection(third)

        assertThat(rows).isEqualTo(3)
        assertThat(metrics.upserted.map { it.date }).containsExactly(first, second, third)
    }

    @Test
    fun `unknown admin metrics are not exposed even when persisted`(): Unit = runBlocking {
        val day = LocalDate.parse("2026-06-01")
        metrics.upsertDailyMetrics(
            listOf(
                AdminDailyMetricPoint(day, "daily_active_users", null, 7.0),
                AdminDailyMetricPoint(day, "unknown_metric", "Redis", 82.5),
            )
        )
        val token = service.login("admin", "secret").adminToken

        val response = service.metrics(token, day, day, emptySet())

        assertThat(response.series.map { it.metricKey }).containsExactly("daily_active_users")
    }

    private class FakeMetricPort : AdminAnalyticsMetricPort {
        val upserted = mutableListOf<AdminDailyMetricPoint>()
        override suspend fun upsertDailyMetrics(points: Collection<AdminDailyMetricPoint>) {
            upserted += points
        }
        override suspend fun findDailyMetrics(startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): List<AdminDailyMetricPoint> =
            upserted.filter { it.date >= startDate && it.date <= endDate && (metricKeys.isEmpty() || it.metricKey in metricKeys) }
    }

    private class FakeSourcePort : AdminAnalyticsSourcePort {
        val rows = mutableMapOf<LocalDate, List<AdminDailyMetricPoint>>()
        override suspend fun collectDailyMetrics(date: LocalDate): List<AdminDailyMetricPoint> = rows[date].orEmpty()
    }

    private class FakeOperatorPort : AdminOperatorPort {
        private val records = linkedMapOf<Long, Record>()
        private var nextId = 1L

        override suspend fun authenticate(
            username: String,
            password: String,
            authenticatedAt: Instant,
        ): AdminOperatorPrincipal? =
            records.values.firstOrNull {
                it.username == username && it.password == password && it.status == "ACTIVE"
            }?.also { it.lastLoginAt = authenticatedAt }?.principal()

        override suspend fun activeStatus(username: String): Boolean? =
            records.values.firstOrNull { it.username == username }?.status?.let { it == "ACTIVE" }

        override suspend fun ensureBootstrap(
            username: String,
            displayName: String,
            password: String,
        ): AdminOperatorPrincipal {
            val existing = records.values.firstOrNull { it.username == username }
            if (existing != null) return existing.principal()
            val record = Record(nextId++, username, displayName, password, "ACTIVE")
            records[record.id] = record
            return record.principal()
        }

        override suspend fun operators(query: String?, limit: Int, offset: Int): AdminOperatorPageResponse {
            val matches = records.values.filter {
                query == null || it.username.contains(query, true) || it.displayName.contains(query, true)
            }
            return AdminOperatorPageResponse(
                matches.drop(offset).take(limit).map(Record::summary),
                matches.size.toLong(),
                limit,
                offset,
            )
        }

        override suspend fun create(
            command: CreateAdminOperatorCommand,
            createdBy: String,
        ): AdminOperatorSummary? {
            if (records.values.any { it.username == command.username }) return null
            val record = Record(nextId++, command.username, command.displayName, command.password, "ACTIVE")
            records[record.id] = record
            return record.summary()
        }

        override suspend fun update(
            operatorId: Long,
            command: UpdateAdminOperatorCommand,
            updatedBy: String,
        ): AdminOperatorSummary? {
            val current = records[operatorId] ?: return null
            current.displayName = command.displayName ?: current.displayName
            current.status = command.status ?: current.status
            current.password = command.password ?: current.password
            return current.summary()
        }

        private data class Record(
            val id: Long,
            val username: String,
            var displayName: String,
            var password: String,
            var status: String,
            var lastLoginAt: Instant? = null,
            val createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        ) {
            fun principal() = AdminOperatorPrincipal(id, username, displayName, status)
            fun summary() = AdminOperatorSummary(
                id,
                username,
                displayName,
                status,
                lastLoginAt,
                createdAt,
                createdAt,
            )
        }
    }
}
