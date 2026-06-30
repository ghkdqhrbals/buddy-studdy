package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystudy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminAnalyticsMetricPort
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

@Repository
class AdminAnalyticsMetricPersistenceAdapter(
    @param:Qualifier("adminAnalyticsJdbcTemplate")
    private val jdbc: NamedParameterJdbcTemplate,
) : AdminAnalyticsMetricPort {
    init {
        ensureSchema()
    }

    fun ensureSchema() {
        jdbc.jdbcTemplate.execute(
            """
            create table if not exists admin_daily_metrics (
                id bigserial primary key,
                metric_date date not null,
                metric_key varchar(80) not null,
                dimension varchar(160) not null default '',
                "value" double precision not null,
                sample_count bigint not null default 0,
                created_at timestamp not null,
                updated_at timestamp not null,
                constraint uq_admin_daily_metrics_day_key_dimension unique (metric_date, metric_key, dimension)
            )
            """.trimIndent()
        )
        jdbc.jdbcTemplate.execute("create index if not exists idx_admin_daily_metrics_key_date on admin_daily_metrics (metric_key, metric_date)")
        jdbc.jdbcTemplate.execute("create index if not exists idx_admin_daily_metrics_date on admin_daily_metrics (metric_date)")
    }

    override fun upsertDailyMetrics(points: Collection<AdminDailyMetricPoint>) {
        val now = Timestamp.from(Instant.now())
        points.forEach { point ->
            val params = MapSqlParameterSource()
                .addValue("metricDate", Date.valueOf(point.date))
                .addValue("metricKey", point.metricKey)
                .addValue("dimension", point.dimension.orEmpty())
                .addValue("value", point.value)
                .addValue("sampleCount", point.sampleCount)
                .addValue("now", now)
            val updated = updateMetric(params)
            if (updated == 0) {
                try {
                    insertMetric(params)
                } catch (_: DuplicateKeyException) {
                    updateMetric(params)
                }
            }
        }
    }

    private fun updateMetric(params: MapSqlParameterSource): Int =
        jdbc.update(
            """
            update admin_daily_metrics
            set "value" = :value,
                sample_count = :sampleCount,
                updated_at = :now
            where metric_date = :metricDate
              and metric_key = :metricKey
              and dimension = :dimension
            """.trimIndent(),
            params,
        )

    private fun insertMetric(params: MapSqlParameterSource) {
        jdbc.update(
                """
                insert into admin_daily_metrics (
                    metric_date,
                    metric_key,
                    dimension,
                    "value",
                    sample_count,
                    created_at,
                    updated_at
                ) values (
                    :metricDate,
                    :metricKey,
                    :dimension,
                    :value,
                    :sampleCount,
                    :now,
                    :now
                )
                """.trimIndent(),
            params,
        )
    }

    override fun findDailyMetrics(startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): List<AdminDailyMetricPoint> {
        val params = MapSqlParameterSource()
            .addValue("startDate", Date.valueOf(startDate))
            .addValue("endDate", Date.valueOf(endDate))
        val metricFilter = if (metricKeys.isEmpty()) {
            ""
        } else {
            params.addValue("metricKeys", metricKeys)
            "and metric_key in (:metricKeys)"
        }
        return jdbc.query(
            """
            select metric_date, metric_key, dimension, "value", sample_count
            from admin_daily_metrics
            where metric_date between :startDate and :endDate
              $metricFilter
            order by metric_key asc, dimension asc, metric_date asc
            """.trimIndent(),
            params,
        ) { resultSet, _ -> resultSet.toPoint() }
    }

    private fun ResultSet.toPoint(): AdminDailyMetricPoint =
        AdminDailyMetricPoint(
            date = getDate("metric_date").toLocalDate(),
            metricKey = getString("metric_key"),
            dimension = getString("dimension").ifBlank { null },
            value = getDouble("value"),
            sampleCount = getLong("sample_count"),
        )
}
