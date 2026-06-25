package com.buddystuddy.backend.admin.analytics.adapter.inbound.web

import com.buddystuddy.backend.admin.analytics.application.model.AdminLoginResponse
import com.buddystuddy.backend.admin.analytics.application.model.AdminMetricsResponse
import com.buddystuddy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/admin")
class AdminAnalyticsController(
    private val admin: AdminAnalyticsWebPort,
) {
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: AdminLoginRequest): AdminLoginResponse =
        admin.login(request)

    @PostMapping("/analytics/refresh")
    fun refresh(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
    ): AdminMetricsResponse =
        admin.refresh(authorization.bearerToken(), startDate, endDate)

    @GetMapping("/analytics/metrics")
    fun metrics(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
        @RequestParam(required = false) metricKey: List<String>?,
    ): AdminMetricsResponse =
        admin.metrics(authorization.bearerToken(), startDate, endDate, metricKey.orEmpty().filter { it.isNotBlank() }.toSet())
}

data class AdminLoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
)

interface AdminAnalyticsWebPort {
    fun login(request: AdminLoginRequest): AdminLoginResponse
    fun refresh(adminToken: String, startDate: LocalDate, endDate: LocalDate): AdminMetricsResponse
    fun metrics(adminToken: String, startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): AdminMetricsResponse
}

@Component
class AdminAnalyticsWebAdapter(
    private val admin: AdminAnalyticsUseCase,
) : AdminAnalyticsWebPort {
    override fun login(request: AdminLoginRequest): AdminLoginResponse =
        admin.login(request.username, request.password)

    override fun refresh(adminToken: String, startDate: LocalDate, endDate: LocalDate): AdminMetricsResponse =
        admin.refresh(adminToken, startDate, endDate)

    override fun metrics(adminToken: String, startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): AdminMetricsResponse =
        admin.metrics(adminToken, startDate, endDate, metricKeys)
}

private fun String?.bearerToken(): String =
    this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
