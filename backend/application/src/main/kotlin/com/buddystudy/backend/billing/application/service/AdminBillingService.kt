package com.buddystudy.backend.billing.application.service

import com.buddystudy.backend.billing.application.model.AdminBillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.AdminBillingInvoicePage
import com.buddystudy.backend.billing.application.model.AdminBillingProcessingFailurePage
import com.buddystudy.backend.billing.application.model.BillingAction
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.AdminQuotaAdjustment
import com.buddystudy.backend.billing.application.model.AdminBillingReconcileRequest
import com.buddystudy.backend.billing.application.model.AdminUserBillingTimeline
import com.buddystudy.backend.billing.application.port.inbound.AdminBillingUseCase
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.billing.domain.InvoiceStatus
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class AdminBillingService(
    private val ledger: BillingLedgerPort,
    private val clock: Clock = Clock.systemUTC(),
) : AdminBillingUseCase {
    override suspend fun invoices(
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminBillingInvoicePage {
        val normalizedStatus = status?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        if (normalizedStatus != null && runCatching { InvoiceStatus.valueOf(normalizedStatus) }.isFailure) {
            throw invalid("Invoice status is invalid.")
        }
        return ledger.adminInvoices(
            query?.trim()?.take(191)?.takeIf(String::isNotEmpty),
            normalizedStatus,
            limit.coerceIn(1, 100),
            offset.coerceAtLeast(0),
        )
    }

    override suspend fun invoice(invoiceId: Long): AdminBillingInvoiceDetail {
        requireInvoiceId(invoiceId)
        return ledger.adminInvoice(invoiceId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice not found.")
    }

    override suspend fun processingFailures(
        source: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminBillingProcessingFailurePage {
        val normalizedSource = source?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        if (normalizedSource != null && normalizedSource !in PROCESSING_FAILURE_SOURCES) {
            throw invalid("Billing processing failure source is invalid.")
        }
        val normalizedStatus = status?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        if (normalizedStatus != null && normalizedStatus !in PROCESSING_FAILURE_STATUSES) {
            throw invalid("Billing processing failure status is invalid.")
        }
        return ledger.adminProcessingFailures(
            normalizedSource,
            normalizedStatus,
            limit.coerceIn(1, 100),
            offset.coerceAtLeast(0),
        )
    }

    override suspend fun requestRefund(
        invoiceId: Long,
        command: RequestBillingActionCommand,
    ): BillingAction {
        requireInvoiceId(invoiceId)
        validate(command)
        return ledger.adminRequestRefund(invoiceId, command.normalized(), clock.instant())
    }

    override suspend fun requestCancellation(
        invoiceId: Long,
        command: RequestBillingActionCommand,
    ): BillingAction {
        requireInvoiceId(invoiceId)
        validate(command)
        return ledger.adminRequestCancellation(invoiceId, command.normalized(), clock.instant())
    }

    override suspend fun adjustQuota(
        userId: Long,
        bonusDelta: Int,
        reason: String,
        idempotencyKey: String,
    ): AdminQuotaAdjustment {
        requireUserId(userId)
        if (bonusDelta == 0 || bonusDelta !in -10_000..10_000) throw invalid("bonusDelta must be between -10000 and 10000 and cannot be zero.")
        val normalizedReason = reason.trim()
        if (normalizedReason.length !in 3..1000) throw invalid("A reason is required.")
        val normalizedKey = idempotencyKey.trim()
        if (!Regex("^[A-Za-z0-9._:-]{8,191}$").matches(normalizedKey)) throw invalid("A valid idempotency key is required.")
        return ledger.adminAdjustQuota(userId, bonusDelta, normalizedReason, normalizedKey, clock.instant())
    }

    override suspend fun reconcile(userId: Long, reason: String?): AdminBillingReconcileRequest {
        requireUserId(userId)
        if ((reason?.length ?: 0) > 1000) throw invalid("Reason is too long.")
        return ledger.adminRequestReconcile(userId, reason?.trim()?.takeIf(String::isNotEmpty), clock.instant())
    }

    override suspend fun timeline(userId: Long, limit: Int): AdminUserBillingTimeline {
        requireUserId(userId)
        return ledger.adminUserTimeline(userId, limit.coerceIn(1, 200))
    }

    private fun requireInvoiceId(invoiceId: Long) {
        if (invoiceId <= 0) throw invalid("invoiceId must be positive.")
    }

    private fun requireUserId(userId: Long) {
        if (userId <= 0) throw invalid("userId must be positive.")
    }

    private fun validate(command: RequestBillingActionCommand) {
        if (!Regex("^[A-Za-z0-9._:-]{8,191}$").matches(command.idempotencyKey.trim())) {
            throw invalid("A valid idempotency key is required.")
        }
        if ((command.reason?.length ?: 0) > 1000) throw invalid("Reason is too long.")
    }

    private fun RequestBillingActionCommand.normalized() = copy(
        idempotencyKey = idempotencyKey.trim(),
        reason = reason?.trim()?.takeIf(String::isNotEmpty),
    )

    private fun invalid(message: String) =
        ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, message)

    private companion object {
        val PROCESSING_FAILURE_SOURCES = setOf("REVENUECAT_EVENT", "SUBSCRIPTION_RECONCILIATION")
        val PROCESSING_FAILURE_STATUSES = setOf("RETRYING", "EXHAUSTED")
    }
}
