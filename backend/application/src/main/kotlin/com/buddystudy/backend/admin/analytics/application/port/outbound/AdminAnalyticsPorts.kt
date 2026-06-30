package com.buddystudy.backend.admin.analytics.application.port.outbound

import com.buddystudy.backend.admin.analytics.application.model.AdminDailyMetricPoint
import java.time.LocalDate

interface AdminAnalyticsMetricPort {
    fun upsertDailyMetrics(points: Collection<AdminDailyMetricPoint>)
    fun findDailyMetrics(startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): List<AdminDailyMetricPoint>
}

interface AdminAnalyticsSourcePort {
    fun collectDailyMetrics(date: LocalDate): List<AdminDailyMetricPoint>
}
