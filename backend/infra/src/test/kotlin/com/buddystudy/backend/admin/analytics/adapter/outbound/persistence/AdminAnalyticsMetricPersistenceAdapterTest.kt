package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystudy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.LocalDate

class AdminAnalyticsMetricPersistenceAdapterTest {
    @Test
    fun `metrics are stored in analytics database`() {
        val serviceDataSource = h2("service")
        val analyticsDataSource = h2("analytics")
        val adapter = AdminAnalyticsMetricPersistenceAdapter(NamedParameterJdbcTemplate(analyticsDataSource))
        val day = LocalDate.parse("2026-06-25")

        adapter.upsertDailyMetrics(listOf(AdminDailyMetricPoint(day, "daily_active_users", null, 3.0, sampleCount = 3)))

        val rows = adapter.findDailyMetrics(day, day, emptySet())
        val serviceTables = serviceDataSource.connection.use { connection ->
            connection.metaData.getTables(null, null, "ADMIN_DAILY_METRICS", null).use { resultSet ->
                generateSequence { if (resultSet.next()) resultSet.getString("TABLE_NAME") else null }.toList()
            }
        }

        assertThat(rows).containsExactly(AdminDailyMetricPoint(day, "daily_active_users", null, 3.0, sampleCount = 3))
        assertThat(serviceTables).isEmpty()
    }

    private fun h2(name: String): DriverManagerDataSource =
        DriverManagerDataSource().apply {
            setDriverClassName("org.h2.Driver")
            url = "jdbc:h2:mem:buddystudy-$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            username = "sa"
            password = ""
        }
}
