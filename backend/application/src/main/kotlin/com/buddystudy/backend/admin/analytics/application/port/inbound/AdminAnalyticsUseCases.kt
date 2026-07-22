package com.buddystudy.backend.admin.analytics.application.port.inbound

import com.buddystudy.backend.admin.analytics.application.model.AdminLoginResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminMetricsResponse
import java.time.LocalDate

interface AdminAnalyticsUseCase {
    suspend fun login(username: String, password: String): AdminLoginResponse
    suspend fun validate(adminToken: String)
    suspend fun refresh(adminToken: String, startDate: LocalDate, endDate: LocalDate): AdminMetricsResponse
    suspend fun metrics(adminToken: String, startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): AdminMetricsResponse
}

interface AdminAnalyticsAggregationUseCase {
    suspend fun refreshRecent(referenceDate: LocalDate): Int
    suspend fun refreshCorrection(referenceDate: LocalDate): Int
    suspend fun refreshRange(startDate: LocalDate, endDate: LocalDate): Int
}
