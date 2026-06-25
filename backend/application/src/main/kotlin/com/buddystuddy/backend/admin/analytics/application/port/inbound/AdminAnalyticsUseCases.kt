package com.buddystuddy.backend.admin.analytics.application.port.inbound

import com.buddystuddy.backend.admin.analytics.application.model.AdminLoginResponse
import com.buddystuddy.backend.admin.analytics.application.model.AdminMetricsResponse
import java.time.LocalDate

interface AdminAnalyticsUseCase {
    fun login(username: String, password: String): AdminLoginResponse
    fun refresh(adminToken: String, startDate: LocalDate, endDate: LocalDate): AdminMetricsResponse
    fun metrics(adminToken: String, startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): AdminMetricsResponse
}
