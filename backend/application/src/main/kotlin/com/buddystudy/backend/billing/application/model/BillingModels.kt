package com.buddystudy.backend.billing.application.model

import com.buddystudy.billing.domain.BillingActionStatus
import com.buddystudy.billing.domain.BillingActionType
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.BillingProductType
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.InvoiceType
import com.buddystudy.billing.domain.PaymentStatus
import java.time.Instant
import java.util.UUID

data class BillingTierProduct(
    val tierCode: String,
    val description: String,
    val monthlyQuestionLimit: Int,
    val productId: String,
    val productType: BillingProductType,
    val billingPeriod: String?,
    val sortOrder: Int,
)

data class BillingCatalog(
    val appAccountToken: UUID,
    val products: List<BillingTierProduct>,
)

data class SyncAppleTransactionCommand(
    val signedTransaction: String,
    val environment: BillingEnvironment,
    val invoiceNumber: UUID? = null,
)

data class CreateBillingCheckoutCommand(
    val productId: String,
    val idempotencyKey: String,
)

data class VerifiedAppleTransaction(
    val transactionId: String,
    val originalTransactionId: String,
    val appTransactionId: String?,
    val webOrderLineItemId: String?,
    val appAccountToken: UUID,
    val productId: String,
    val productType: BillingProductType,
    val environment: BillingEnvironment,
    val quantity: Int,
    val priceMilliunits: Long?,
    val currency: String?,
    val purchaseAt: Instant,
    val originalPurchaseAt: Instant?,
    val expiresAt: Instant?,
    val revocationAt: Instant?,
    val revocationReason: Int?,
    val signedAt: Instant,
    val signedPayloadSha256: String,
)

data class VerifiedAppleNotification(
    val notificationUUID: String,
    val notificationType: String,
    val subtype: String?,
    val environment: BillingEnvironment,
    val signedAt: Instant,
    val signedPayloadSha256: String,
    val transaction: VerifiedAppleTransaction?,
)

data class RevenueCatWebhookRequest(
    val rawBody: ByteArray,
    val signature: String,
)

data class VerifiedRevenueCatEvent(
    val eventId: String,
    val eventType: String,
    val appUserId: String?,
    val aliases: List<String>,
    val store: String?,
    val productId: String?,
    val transactionId: String?,
    val originalTransactionId: String?,
    val environment: BillingEnvironment?,
    val priceMilliunits: Long?,
    val currency: String?,
    val purchasedAt: Instant?,
    val expiresAt: Instant?,
    val eventAt: Instant,
    val cancelReason: String?,
    val signedPayloadSha256: String,
)

data class BillingInvoiceSummary(
    val id: Long,
    val invoiceNumber: UUID,
    val type: InvoiceType,
    val originalInvoiceId: Long?,
    val tierCode: String,
    val productId: String,
    val status: InvoiceStatus,
    val version: Long,
    val paymentId: Long?,
    val transactionId: String?,
    val originalTransactionId: String?,
    val paymentStatus: PaymentStatus?,
    val priceMilliunits: Long?,
    val currency: String?,
    val purchaseAt: Instant?,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class BillingInvoiceEvent(
    val eventId: String,
    val sequenceNumber: Long,
    val eventType: String,
    val source: BillingEventSource,
    val fromStatus: InvoiceStatus?,
    val toStatus: InvoiceStatus,
    val reason: String?,
    val occurredAt: Instant,
)

data class PaymentHistoryEntry(
    val eventId: String,
    val eventType: String,
    val source: BillingEventSource,
    val fromStatus: PaymentStatus?,
    val toStatus: PaymentStatus,
    val reason: String?,
    val occurredAt: Instant,
)

data class BillingInvoiceDetail(
    val invoice: BillingInvoiceSummary,
    val events: List<BillingInvoiceEvent>,
    val paymentHistory: List<PaymentHistoryEntry>,
    val actions: List<BillingAction>,
)

data class BillingInvoicePage(
    val limit: Int,
    val offset: Int,
    val invoices: List<BillingInvoiceSummary>,
)

data class BillingAction(
    val actionId: UUID,
    val actionType: BillingActionType,
    val status: BillingActionStatus,
    val invoiceId: Long,
    val paymentId: Long,
    val providerTransactionId: String,
    val providerOriginalTransactionId: String,
    val reason: String?,
    val requestedAt: Instant,
    val completedAt: Instant?,
    val clientAction: BillingClientAction,
)

data class AdminBillingInvoice(
    val userId: Long,
    val userEmail: String,
    val userDisplayName: String,
    val invoice: BillingInvoiceSummary,
)

data class AdminBillingInvoicePage(
    val limit: Int,
    val offset: Int,
    val totalCount: Long,
    val invoices: List<AdminBillingInvoice>,
)

data class AdminBillingInvoiceDetail(
    val userId: Long,
    val userEmail: String,
    val userDisplayName: String,
    val detail: BillingInvoiceDetail,
)

enum class BillingClientAction {
    NONE,
    BEGIN_APPLE_REFUND_REQUEST,
    OPEN_APPLE_SUBSCRIPTION_MANAGEMENT,
}

data class RequestBillingActionCommand(
    val idempotencyKey: String,
    val reason: String?,
)

data class RecordVerifiedPaymentCommand(
    val userId: Long,
    val tierProduct: BillingTierProduct,
    val transaction: VerifiedAppleTransaction,
    val invoiceNumber: UUID?,
    val source: BillingEventSource,
    val eventId: String,
    val occurredAt: Instant,
)

data class ApplyAppleNotificationCommand(
    val notification: VerifiedAppleNotification,
    val occurredAt: Instant,
)

data class BillingFulfillmentJobClaim(
    val jobId: Long,
    val invoiceId: Long,
    val attempts: Int,
    val maxAttempts: Int,
    val claimToken: UUID,
)

data class BillingRecoveryResult(
    val claimed: Int,
    val completed: Int,
    val retried: Int,
    val compensationRequired: Int,
)
