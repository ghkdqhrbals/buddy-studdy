package com.buddystudy.backend.admin.analytics.application.model

import java.time.Instant
import java.time.LocalDate

data class AdminLoginResponse(
    val adminToken: String,
    val expiresAt: Instant,
)

data class AdminDailyMetricPoint(
    val date: LocalDate,
    val metricKey: String,
    val dimension: String?,
    val value: Double,
    val sampleCount: Long = 0,
)

data class AdminMetricSeries(
    val metricKey: String,
    val dimension: String?,
    val points: List<AdminDailyMetricPoint>,
)

data class AdminMetricsResponse(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val series: List<AdminMetricSeries>,
)
