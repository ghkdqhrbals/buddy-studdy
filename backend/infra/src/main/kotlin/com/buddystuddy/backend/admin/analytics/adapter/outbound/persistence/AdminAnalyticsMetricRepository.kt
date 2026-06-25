package com.buddystuddy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystuddy.admin.domain.entity.AdminDailyMetricEntity
import com.buddystuddy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import com.buddystuddy.backend.admin.analytics.application.port.outbound.AdminAnalyticsMetricPort
import jakarta.persistence.EntityManager
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

interface AdminDailyMetricJpaRepository : JpaRepository<AdminDailyMetricEntity, Long> {
    fun findByMetricDateBetweenOrderByMetricKeyAscDimensionAscMetricDateAsc(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<AdminDailyMetricEntity>

    @Query(
        """
        select m from AdminDailyMetricEntity m
        where m.metricDate between :startDate and :endDate
          and m.metricKey in :metricKeys
        order by m.metricKey asc, m.dimension asc, m.metricDate asc
        """
    )
    fun findSeries(
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
        @Param("metricKeys") metricKeys: Set<String>,
    ): List<AdminDailyMetricEntity>
}

@Repository
class AdminAnalyticsMetricPersistenceAdapter(
    private val jpa: AdminDailyMetricJpaRepository,
    private val entityManager: EntityManager,
) : AdminAnalyticsMetricPort {
    @Transactional
    override fun upsertDailyMetrics(points: Collection<AdminDailyMetricPoint>) {
        val now = Instant.now()
        points.forEach { point ->
            entityManager.createNativeQuery(
                """
                insert into admin_daily_metrics (
                    metric_date,
                    metric_key,
                    dimension,
                    value,
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
                on conflict (metric_date, metric_key, dimension)
                do update set
                    value = excluded.value,
                    sample_count = excluded.sample_count,
                    updated_at = excluded.updated_at
                """.trimIndent()
            )
                .setParameter("metricDate", Date.valueOf(point.date))
                .setParameter("metricKey", point.metricKey)
                .setParameter("dimension", point.dimension.orEmpty())
                .setParameter("value", point.value)
                .setParameter("sampleCount", point.sampleCount)
                .setParameter("now", Timestamp.from(now))
                .executeUpdate()
        }
    }

    @Transactional(readOnly = true)
    override fun findDailyMetrics(startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): List<AdminDailyMetricPoint> =
        (if (metricKeys.isEmpty()) {
            jpa.findByMetricDateBetweenOrderByMetricKeyAscDimensionAscMetricDateAsc(startDate, endDate)
        } else {
            jpa.findSeries(startDate, endDate, metricKeys)
        }).map {
            AdminDailyMetricPoint(
                date = it.metricDate,
                metricKey = it.metricKey,
                dimension = it.dimension.ifBlank { null },
                value = it.value,
                sampleCount = it.sampleCount,
            )
        }
}
