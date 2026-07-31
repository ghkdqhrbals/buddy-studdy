package com.buddystudy.backend.admin.analytics.application.port.inbound

import com.buddystudy.backend.admin.analytics.application.model.AdminLoginResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminMetricsResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorPageResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorSummary
import com.buddystudy.backend.admin.analytics.application.model.AdminSessionResponse
import com.buddystudy.backend.admin.analytics.application.model.CreateAdminOperatorCommand
import com.buddystudy.backend.admin.analytics.application.model.UpdateAdminOperatorCommand
import java.time.LocalDate

interface AdminAnalyticsUseCase {
    suspend fun login(username: String, password: String): AdminLoginResponse
    suspend fun validate(adminToken: String)
    suspend fun session(adminToken: String): AdminSessionResponse
    suspend fun operators(adminToken: String, query: String?, limit: Int, offset: Int): AdminOperatorPageResponse
    suspend fun createOperator(adminToken: String, command: CreateAdminOperatorCommand): AdminOperatorSummary
    suspend fun updateOperator(
        adminToken: String,
        operatorId: Long,
        command: UpdateAdminOperatorCommand,
    ): AdminOperatorSummary
    suspend fun refresh(adminToken: String, startDate: LocalDate, endDate: LocalDate): AdminMetricsResponse
    suspend fun metrics(adminToken: String, startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): AdminMetricsResponse
}

interface AdminAnalyticsAggregationUseCase {
    suspend fun refreshRecent(referenceDate: LocalDate): Int
    suspend fun refreshCorrection(referenceDate: LocalDate): Int
    suspend fun refreshRange(startDate: LocalDate, endDate: LocalDate): Int
}
