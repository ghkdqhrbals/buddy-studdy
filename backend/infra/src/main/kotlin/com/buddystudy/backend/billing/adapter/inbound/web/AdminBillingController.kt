package com.buddystudy.backend.billing.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.billing.application.model.AdminBillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.AdminBillingInvoicePage
import com.buddystudy.backend.billing.application.model.BillingAction
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.AdminQuotaAdjustment
import com.buddystudy.backend.billing.application.model.AdminBillingReconcileRequest
import com.buddystudy.backend.billing.application.model.AdminUserBillingTimeline
import com.buddystudy.backend.billing.application.port.inbound.AdminBillingUseCase
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
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

@RestController
@RequestMapping("/api/v1/admin/billing")
class AdminBillingController(
    private val billing: AdminBillingWebPort,
) {
    @GetMapping("/invoices")
    suspend fun invoices(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "30") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): AdminBillingInvoicePage = billing.invoices(authorization.bearerToken(), query, status, limit, offset)

    @GetMapping("/invoices/{invoiceId}")
    suspend fun invoice(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable invoiceId: Long,
    ): AdminBillingInvoiceDetail = billing.invoice(authorization.bearerToken(), invoiceId)

    @PostMapping("/invoices/{invoiceId}/refund-requests")
    suspend fun requestRefund(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable invoiceId: Long,
        @Valid @RequestBody request: AdminBillingActionRequest,
    ): BillingAction = billing.requestRefund(authorization.bearerToken(), invoiceId, request)

    @PostMapping("/invoices/{invoiceId}/cancellation-requests")
    suspend fun requestCancellation(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable invoiceId: Long,
        @Valid @RequestBody request: AdminBillingActionRequest,
    ): BillingAction = billing.requestCancellation(authorization.bearerToken(), invoiceId, request)
}

@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserBillingController(
    private val billing: AdminBillingWebPort,
) {
    @GetMapping("/{userId}/billing/timeline")
    suspend fun timeline(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable userId: Long,
        @RequestParam(defaultValue = "100") limit: Int,
    ): AdminUserBillingTimeline = billing.timeline(authorization.bearerToken(), userId, limit)

    @PostMapping("/{userId}/quota-adjustments")
    suspend fun adjustQuota(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable userId: Long,
        @Valid @RequestBody request: AdminQuotaAdjustmentRequest,
    ): AdminQuotaAdjustment = billing.adjustQuota(authorization.bearerToken(), userId, request)

    @PostMapping("/{userId}/billing/reconcile")
    suspend fun reconcile(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable userId: Long,
        @Valid @RequestBody request: AdminBillingReconcileRequestBody,
    ): AdminBillingReconcileRequest = billing.reconcile(authorization.bearerToken(), userId, request.reason)
}

data class AdminQuotaAdjustmentRequest(
    var bonusDelta: Int = 0,
    @field:NotBlank @field:Size(min = 3, max = 1000)
    var reason: String = "",
    @field:NotBlank @field:Size(min = 8, max = 191)
    @field:Pattern(regexp = "[A-Za-z0-9._:-]+")
    var idempotencyKey: String = "",
)

data class AdminBillingReconcileRequestBody(
    @field:Size(max = 1000)
    var reason: String? = null,
)

data class AdminBillingActionRequest(
    @field:NotBlank
    @field:Size(min = 8, max = 191)
    @field:Pattern(regexp = "[A-Za-z0-9._:-]+")
    var idempotencyKey: String = "",
    @field:Size(max = 1000)
    var reason: String? = null,
)

interface AdminBillingWebPort {
    suspend fun invoices(
        adminToken: String,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminBillingInvoicePage
    suspend fun invoice(adminToken: String, invoiceId: Long): AdminBillingInvoiceDetail
    suspend fun requestRefund(adminToken: String, invoiceId: Long, request: AdminBillingActionRequest): BillingAction
    suspend fun requestCancellation(adminToken: String, invoiceId: Long, request: AdminBillingActionRequest): BillingAction
    suspend fun adjustQuota(
        adminToken: String,
        userId: Long,
        request: AdminQuotaAdjustmentRequest,
    ): AdminQuotaAdjustment
    suspend fun reconcile(adminToken: String, userId: Long, reason: String?): AdminBillingReconcileRequest
    suspend fun timeline(adminToken: String, userId: Long, limit: Int): AdminUserBillingTimeline
}

@Component
class AdminBillingWebAdapter(
    private val authentication: AdminAnalyticsUseCase,
    private val billing: AdminBillingUseCase,
) : AdminBillingWebPort {
    override suspend fun invoices(
        adminToken: String,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminBillingInvoicePage {
        authentication.validate(adminToken)
        return billing.invoices(query, status, limit, offset)
    }

    override suspend fun invoice(adminToken: String, invoiceId: Long): AdminBillingInvoiceDetail {
        authentication.validate(adminToken)
        return billing.invoice(invoiceId)
    }

    override suspend fun requestRefund(
        adminToken: String,
        invoiceId: Long,
        request: AdminBillingActionRequest,
    ): BillingAction {
        authentication.validate(adminToken)
        return billing.requestRefund(invoiceId, RequestBillingActionCommand(request.idempotencyKey, request.reason))
    }

    override suspend fun requestCancellation(
        adminToken: String,
        invoiceId: Long,
        request: AdminBillingActionRequest,
    ): BillingAction {
        authentication.validate(adminToken)
        return billing.requestCancellation(invoiceId, RequestBillingActionCommand(request.idempotencyKey, request.reason))
    }

    override suspend fun adjustQuota(
        adminToken: String,
        userId: Long,
        request: AdminQuotaAdjustmentRequest,
    ): AdminQuotaAdjustment {
        authentication.validate(adminToken)
        return billing.adjustQuota(userId, request.bonusDelta, request.reason, request.idempotencyKey)
    }

    override suspend fun reconcile(
        adminToken: String,
        userId: Long,
        reason: String?,
    ): AdminBillingReconcileRequest {
        authentication.validate(adminToken)
        return billing.reconcile(userId, reason)
    }

    override suspend fun timeline(adminToken: String, userId: Long, limit: Int): AdminUserBillingTimeline {
        authentication.validate(adminToken)
        return billing.timeline(userId, limit)
    }
}

private fun String?.bearerToken(): String {
    val value = this?.trim().orEmpty()
    if (!value.startsWith("Bearer ") || value.length <= 7) {
        throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED, "Admin token is required.")
    }
    return value.removePrefix("Bearer ").trim()
}
