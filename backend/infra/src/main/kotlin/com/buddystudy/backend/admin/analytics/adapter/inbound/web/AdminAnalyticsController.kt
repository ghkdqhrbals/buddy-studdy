package com.buddystudy.backend.admin.analytics.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.model.AdminLoginResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminMetricsResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorPageResponse
import com.buddystudy.backend.admin.analytics.application.model.AdminOperatorSummary
import com.buddystudy.backend.admin.analytics.application.model.AdminSessionResponse
import com.buddystudy.backend.admin.analytics.application.model.CreateAdminOperatorCommand
import com.buddystudy.backend.admin.analytics.application.model.UpdateAdminOperatorCommand
import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystudy.backend.scheduler.application.model.ScheduledJobStatusResponse
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/admin")
class AdminAnalyticsController(
    private val admin: AdminAnalyticsWebPort,
) {
    @PostMapping("/login")
    suspend fun login(@Valid @RequestBody request: AdminLoginRequest): AdminLoginResponse =
        admin.login(request)

    @GetMapping("/session")
    suspend fun session(
        @RequestHeader("Authorization") authorization: String?,
    ): AdminSessionResponse =
        admin.session(authorization.bearerToken())

    @GetMapping("/operators")
    suspend fun operators(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): AdminOperatorPageResponse =
        admin.operators(authorization.bearerToken(), query, limit, offset)

    @PostMapping("/operators")
    suspend fun createOperator(
        @RequestHeader("Authorization") authorization: String?,
        @Valid @RequestBody request: CreateAdminOperatorRequest,
    ): AdminOperatorSummary =
        admin.createOperator(authorization.bearerToken(), request)

    @PatchMapping("/operators/{operatorId}")
    suspend fun updateOperator(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable operatorId: Long,
        @Valid @RequestBody request: UpdateAdminOperatorRequest,
    ): AdminOperatorSummary =
        admin.updateOperator(authorization.bearerToken(), operatorId, request)

    @PostMapping("/analytics/refresh")
    suspend fun refresh(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
    ): AdminMetricsResponse =
        admin.refresh(authorization.bearerToken(), startDate, endDate)

    @GetMapping("/analytics/metrics")
    suspend fun metrics(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
        @RequestParam(required = false) metricKey: List<String>?,
    ): AdminMetricsResponse =
        admin.metrics(authorization.bearerToken(), startDate, endDate, metricKey.orEmpty().filter { it.isNotBlank() }.toSet())

    @GetMapping("/jobs/runs")
    suspend fun jobRuns(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(required = false) jobName: String?,
        @RequestParam(required = false) runId: Long?,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ScheduledJobRunPageResponse =
        admin.jobRuns(authorization.bearerToken(), jobName?.takeIf { it.isNotBlank() }, runId, limit, offset)

    @PostMapping("/jobs/{jobName}/retry")
    suspend fun retryJob(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable jobName: String,
        @RequestParam(required = false) runId: Long?,
    ): ScheduledJobRun =
        admin.retryJob(authorization.bearerToken(), jobName, runId)

    @GetMapping("/jobs/statuses")
    suspend fun jobStatuses(
        @RequestHeader("Authorization") authorization: String?,
    ): ScheduledJobStatusResponse =
        admin.jobStatuses(authorization.bearerToken())
}

data class AdminLoginRequest(
    @field:NotBlank var username: String = "",
    @field:NotBlank var password: String = "",
)

data class CreateAdminOperatorRequest(
    @field:NotBlank @field:Size(min = 3, max = 64)
    var username: String = "",
    @field:NotBlank @field:Size(min = 2, max = 100)
    var displayName: String = "",
    @field:NotBlank @field:Size(min = 12, max = 128)
    var password: String = "",
)

data class UpdateAdminOperatorRequest(
    @field:Size(min = 2, max = 100)
    var displayName: String? = null,
    var status: String? = null,
    @field:Size(min = 12, max = 128)
    var password: String? = null,
)

interface AdminAnalyticsWebPort {
    suspend fun login(request: AdminLoginRequest): AdminLoginResponse
    suspend fun session(adminToken: String): AdminSessionResponse
    suspend fun operators(adminToken: String, query: String?, limit: Int, offset: Int): AdminOperatorPageResponse
    suspend fun createOperator(adminToken: String, request: CreateAdminOperatorRequest): AdminOperatorSummary
    suspend fun updateOperator(
        adminToken: String,
        operatorId: Long,
        request: UpdateAdminOperatorRequest,
    ): AdminOperatorSummary
    suspend fun refresh(adminToken: String, startDate: LocalDate, endDate: LocalDate): AdminMetricsResponse
    suspend fun metrics(adminToken: String, startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): AdminMetricsResponse
    suspend fun jobRuns(adminToken: String, jobName: String?, runId: Long?, limit: Int, offset: Int): ScheduledJobRunPageResponse
    suspend fun retryJob(adminToken: String, jobName: String, runId: Long?): ScheduledJobRun
    suspend fun jobStatuses(adminToken: String): ScheduledJobStatusResponse
}

@Component
class AdminAnalyticsWebAdapter(
    private val admin: AdminAnalyticsUseCase,
    private val jobExecutions: ManagedJobExecutionUseCase,
    jobs: List<ManagedJob>,
) : AdminAnalyticsWebPort {
    private val jobsByName = jobs.associateBy { it.name }

    override suspend fun login(request: AdminLoginRequest): AdminLoginResponse =
        admin.login(request.username, request.password)

    override suspend fun session(adminToken: String): AdminSessionResponse =
        admin.session(adminToken)

    override suspend fun operators(
        adminToken: String,
        query: String?,
        limit: Int,
        offset: Int,
    ): AdminOperatorPageResponse =
        admin.operators(adminToken, query, limit, offset)

    override suspend fun createOperator(
        adminToken: String,
        request: CreateAdminOperatorRequest,
    ): AdminOperatorSummary =
        admin.createOperator(
            adminToken,
            CreateAdminOperatorCommand(request.username, request.displayName, request.password),
        )

    override suspend fun updateOperator(
        adminToken: String,
        operatorId: Long,
        request: UpdateAdminOperatorRequest,
    ): AdminOperatorSummary =
        admin.updateOperator(
            adminToken,
            operatorId,
            UpdateAdminOperatorCommand(request.displayName, request.status, request.password),
        )

    override suspend fun refresh(adminToken: String, startDate: LocalDate, endDate: LocalDate): AdminMetricsResponse =
        admin.refresh(adminToken, startDate, endDate)

    override suspend fun metrics(adminToken: String, startDate: LocalDate, endDate: LocalDate, metricKeys: Set<String>): AdminMetricsResponse =
        admin.metrics(adminToken, startDate, endDate, metricKeys)

    override suspend fun jobRuns(adminToken: String, jobName: String?, runId: Long?, limit: Int, offset: Int): ScheduledJobRunPageResponse {
        admin.validate(adminToken)
        return jobExecutions.findRuns(jobName, runId, limit, offset)
    }

    override suspend fun retryJob(adminToken: String, jobName: String, runId: Long?): ScheduledJobRun {
        admin.validate(adminToken)
        val job = jobsByName[jobName]
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Scheduled job not found.")
        return jobExecutions.execute(job, JobTriggerType.RETRY, retryOfRunId = runId, createdBy = "admin")
    }

    override suspend fun jobStatuses(adminToken: String): ScheduledJobStatusResponse {
        admin.validate(adminToken)
        return jobExecutions.findStatuses()
    }
}

private fun String?.bearerToken(): String =
    this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
