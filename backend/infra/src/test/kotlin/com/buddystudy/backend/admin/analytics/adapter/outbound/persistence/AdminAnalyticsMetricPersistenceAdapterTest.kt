package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystudy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import java.time.LocalDate

class AdminAnalyticsMetricPersistenceAdapterTest {
    @Test
    fun `metrics are stored through the analytics r2dbc client`(): Unit = runBlocking {
        val serviceClient = client("service")
        val analyticsClient = client("analytics")
        val adapter = AdminAnalyticsMetricPersistenceAdapter(analyticsClient)
        val day = LocalDate.parse("2026-06-25")

        adapter.upsertDailyMetrics(listOf(AdminDailyMetricPoint(day, "daily_active_users", null, 3.0, sampleCount = 3)))

        val rows = adapter.findDailyMetrics(day, day, emptySet())
        val serviceTableCount = serviceClient.sql(
            "select count(*) as total from information_schema.tables where table_name = 'admin_daily_metrics'",
        ).map { row, _ -> (row.get("total") as Number).toLong() }.one().awaitSingle()

        assertThat(rows).containsExactly(AdminDailyMetricPoint(day, "daily_active_users", null, 3.0, sampleCount = 3))
        assertThat(serviceTableCount).isZero()
    }

    private fun client(name: String) = DatabaseClient.create(
        ConnectionFactories.get("r2dbc:h2:mem:///admin-$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"),
    )
}
