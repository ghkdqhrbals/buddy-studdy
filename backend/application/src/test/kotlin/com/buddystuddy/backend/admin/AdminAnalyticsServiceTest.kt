package com.buddystuddy.backend.admin

import com.buddystuddy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import com.buddystuddy.backend.admin.analytics.application.port.outbound.AdminAnalyticsMetricPort
import com.buddystuddy.backend.admin.analytics.application.port.outbound.AdminAnalyticsSourcePort
import com.buddystuddy.backend.admin.analytics.application.service.AdminAnalyticsService
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AdminAnalyticsServiceTest {
    private val properties = BuddyStuddyProperties().apply {
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
            BuddyStuddyProperties().apply { crypto.masterKey = "test-master-key" },
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
    fun `topic score trend is not exposed even when persisted`() {
        val day = LocalDate.parse("2026-06-01")
        metrics.upsertDailyMetrics(
            listOf(
                AdminDailyMetricPoint(day, "daily_active_users", null, 7.0),
                AdminDailyMetricPoint(day, "topic_score_trend", "Redis", 82.5),
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
