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
        analyticsClient.sql(
            """
            create table admin_daily_metrics (
                id bigint auto_increment primary key,
                metric_date date not null,
                metric_key varchar(80) not null,
                dimension varchar(255) not null default '',
                `value` double not null,
                sample_count bigint not null default 0,
                created_at timestamp not null,
                updated_at timestamp not null,
                constraint uq_admin_daily_metrics_day_key_dimension unique (metric_date, metric_key, dimension)
            )
            """.trimIndent(),
        ).fetch().rowsUpdated().awaitSingle()
        val adapter = AdminAnalyticsMetricPersistenceAdapter(AdminAnalyticsDatabaseClient(analyticsClient))
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
        ConnectionFactories.get("r2dbc:h2:mem:///admin-$name;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"),
    )
}
