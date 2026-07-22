package com.buddystudy.backend.admin.analytics.application.port.outbound

import com.buddystudy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import java.time.LocalDate

interface AdminAnalyticsMetricPort {
    suspend fun upsertDailyMetrics(points: Collection<AdminDailyMetricPoint>)
    suspend fun findDailyMetrics(startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): List<AdminDailyMetricPoint>
}

interface AdminAnalyticsSourcePort {
    suspend fun collectDailyMetrics(date: LocalDate): List<AdminDailyMetricPoint>
}
