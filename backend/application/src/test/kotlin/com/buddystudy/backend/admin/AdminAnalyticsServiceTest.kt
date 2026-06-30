package com.buddystudy.backend.admin

import com.buddystudy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminAnalyticsMetricPort
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminAnalyticsSourcePort
import com.buddystudy.backend.admin.analytics.application.service.AdminAnalyticsService
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AdminAnalyticsServiceTest {
    private val properties = BuddyStudyProperties().apply {
        crypto.masterKey = "test-master-key"
        admin.username = "admin"
        admin.password = "secret"
    }
    private val metrics = FakeMetricPort()
    private val source = FakeSourcePort()
    private val service = AdminAnalyticsService(properties, metrics, source)

    @Test
    fun `default admin credentials are admin admin`() {
        val defaultService = AdminAnalyticsService(
            BuddyStudyProperties().apply { crypto.masterKey = "test-master-key" },
            FakeMetricPort(),
            FakeSourcePort(),
        )

        val token = defaultService.login("admin", "admin").adminToken

        assertThat(token).isNotBlank()
    }

    @Test
    fun `admin login returns token and metrics require valid admin token`() {
        assertThatThrownBy { service.metrics("bad-token", LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-01"), emptySet()) }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN)

        val token = service.login("admin", "secret").adminToken
        val response = service.metrics(token, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-01"), emptySet())

        assertThat(response.series).isEmpty()
    }

    @Test
    fun `admin token validation rejects tampered token`() {
        val token = service.login("admin", "secret").adminToken
        val tampered = token.substringBeforeLast(".") + ".tampered"

        assertThatThrownBy { service.validate(tampered) }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN)
    }

    @Test
    fun `refresh stores daily source metrics and returns time series`() {
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
    fun `scheduled recent refresh recomputes configured recent date range`() {
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
    fun `scheduled correction refresh recomputes configured correction date range`() {
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
    fun `unknown admin metrics are not exposed even when persisted`() {
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
        override fun upsertDailyMetrics(points: Collection<AdminDailyMetricPoint>) {
            upserted += points
        }
        override fun findDailyMetrics(startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): List<AdminDailyMetricPoint> =
            upserted.filter { it.date >= startDate && it.date <= endDate && (metricKeys.isEmpty() || it.metricKey in metricKeys) }
    }

    private class FakeSourcePort : AdminAnalyticsSourcePort {
        val rows = mutableMapOf<LocalDate, List<AdminDailyMetricPoint>>()
        override fun collectDailyMetrics(date: LocalDate): List<AdminDailyMetricPoint> = rows[date].orEmpty()
    }
}
