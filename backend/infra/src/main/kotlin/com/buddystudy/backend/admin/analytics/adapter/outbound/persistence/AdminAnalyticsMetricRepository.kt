package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystudy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import com.buddystudy.backend.admin.analytics.application.port.outbound.AdminAnalyticsMetricPort
import com.buddystudy.backend.common.adapter.outbound.persistence.bindIndexed
import com.buddystudy.backend.common.adapter.outbound.persistence.indexedBindMarkers
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

@Repository
class AdminAnalyticsMetricPersistenceAdapter(
    analyticsClient: AdminAnalyticsDatabaseClient,
) : AdminAnalyticsMetricPort {
    private val client = analyticsClient.client

    override suspend fun upsertDailyMetrics(points: Collection<AdminDailyMetricPoint>) {
        val now = Instant.now()
        points.forEach { point ->
            val updated = client.sql(
                """
                update admin_daily_metrics set
                    `value` = :value, sample_count = :sampleCount, updated_at = :now
                where metric_date = :date and metric_key = :key and dimension = :dimension
                """.trimIndent(),
            ).bind("date", point.date).bind("key", point.metricKey).bind("dimension", point.dimension.orEmpty())
                .bind("value", point.value).bind("sampleCount", point.sampleCount).bind("now", now)
                .fetch().rowsUpdated().awaitSingle()
            if (updated > 0) return@forEach

            client.sql(
                """
                insert into admin_daily_metrics
                    (metric_date, metric_key, dimension, `value`, sample_count, created_at, updated_at)
                values (:date, :key, :dimension, :value, :sampleCount, :now, :now)
                """.trimIndent(),
            ).bind("date", point.date).bind("key", point.metricKey).bind("dimension", point.dimension.orEmpty())
                .bind("value", point.value).bind("sampleCount", point.sampleCount).bind("now", now)
                .fetch().rowsUpdated().awaitSingle()
        }
    }

    override suspend fun findDailyMetrics(
        startDate: LocalDate,
        endDate: LocalDate,
        metricKeys: Set<String>,
    ): List<AdminDailyMetricPoint> {
        val filter = if (metricKeys.isEmpty()) "" else "and metric_key in (${indexedBindMarkers("metricKey", metricKeys.size)})"
        var spec = client.sql(
            """
            select metric_date, metric_key, dimension, `value`, sample_count from admin_daily_metrics
            where metric_date between :startDate and :endDate $filter
            order by metric_key asc, dimension asc, metric_date asc
            """.trimIndent(),
        ).bind("startDate", startDate).bind("endDate", endDate)
        if (metricKeys.isNotEmpty()) spec = spec.bindIndexed("metricKey", metricKeys.toList())
        return spec.map { row, _ ->
            AdminDailyMetricPoint(
                date = row.get("metric_date", LocalDate::class.java)!!,
                metricKey = row.get("metric_key", String::class.java)!!,
                dimension = row.get("dimension", String::class.java)!!.ifBlank { null },
                value = (row.get("value") as Number).toDouble(),
                sampleCount = (row.get("sample_count") as Number).toLong(),
            )
        }.all().collectList().awaitSingle()
    }
}
