package com.buddystuddy.backend.admin.analytics.adapter.inbound.web

import com.buddystuddy.backend.admin.analytics.application.model.AdminLoginResponse
import com.buddystuddy.backend.admin.analytics.application.model.AdminMetricsResponse
import com.buddystuddy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.scheduler.application.model.JobTriggerType
import com.buddystuddy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystuddy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystuddy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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

    @GetMapping("/jobs/runs")
    fun jobRuns(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(required = false) jobName: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<ScheduledJobRun> =
        admin.jobRuns(authorization.bearerToken(), jobName?.takeIf { it.isNotBlank() }, limit)

    @PostMapping("/jobs/{jobName}/retry")
    fun retryJob(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable jobName: String,
        @RequestParam(required = false) runId: Long?,
    ): ScheduledJobRun =
        admin.retryJob(authorization.bearerToken(), jobName, runId)
}

data class AdminLoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
)

interface AdminAnalyticsWebPort {
    fun login(request: AdminLoginRequest): AdminLoginResponse
    fun refresh(adminToken: String, startDate: LocalDate, endDate: LocalDate): AdminMetricsResponse
    fun metrics(adminToken: String, startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): AdminMetricsResponse
    fun jobRuns(adminToken: String, jobName: String?, limit: Int): List<ScheduledJobRun>
    fun retryJob(adminToken: String, jobName: String, runId: Long?): ScheduledJobRun
}

@Component
class AdminAnalyticsWebAdapter(
    private val admin: AdminAnalyticsUseCase,
    private val jobExecutions: ManagedJobExecutionUseCase,
    jobs: List<ManagedJob>,
) : AdminAnalyticsWebPort {
    private val jobsByName = jobs.associateBy { it.name }

    override fun login(request: AdminLoginRequest): AdminLoginResponse =
        admin.login(request.username, request.password)

    override fun refresh(adminToken: String, startDate: LocalDate, endDate: LocalDate): AdminMetricsResponse =
        admin.refresh(adminToken, startDate, endDate)

    override fun metrics(adminToken: String, startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): AdminMetricsResponse =
        admin.metrics(adminToken, startDate, endDate, metricKeys)

    override fun jobRuns(adminToken: String, jobName: String?, limit: Int): List<ScheduledJobRun> {
        admin.validate(adminToken)
        return jobExecutions.findRuns(jobName, limit)
    }

    override fun retryJob(adminToken: String, jobName: String, runId: Long?): ScheduledJobRun {
        admin.validate(adminToken)
        val job = jobsByName[jobName]
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Scheduled job not found.")
        return jobExecutions.execute(job, JobTriggerType.RETRY, retryOfRunId = runId, createdBy = "admin")
    }
}

private fun String?.bearerToken(): String =
    this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
