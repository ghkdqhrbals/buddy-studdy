package com.buddystudy.backend.billing.application.model

import com.buddystudy.billing.domain.BillingActionStatus
import com.buddystudy.billing.domain.BillingActionType
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.BillingProductType
import com.buddystudy.billing.domain.InvoiceEventType
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.InvoiceType
import com.buddystudy.billing.domain.PaymentStatus
import com.buddystudy.billing.domain.EntitlementSource
import com.buddystudy.billing.domain.SubscriptionAccessStatus
import com.buddystudy.billing.domain.SubscriptionRenewalStatus
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

data class BillingEntitlementProjection(
    val tierCode: String,
    val source: EntitlementSource,
    val accessStatus: SubscriptionAccessStatus,
    val renewalStatus: SubscriptionRenewalStatus,
    val productId: String?,
    val startedAt: Instant?,
    val expiresAt: Instant?,
    val willRenew: Boolean,
    val pendingProductId: String?,
    val synchronizedAt: Instant,
)

data class BillingQuotaStatus(
    val periodStartedAt: Instant,
    val resetAt: Instant,
    val anchorType: String,
    val baseLimit: Int,
    val bonusLimit: Int,
    val usedCount: Int,
    val reservedCount: Int,
    val remainingCount: Int,
    val policyVersion: Int,
)

data class BillingStatusResponse(
    val tierCode: String,
    val source: EntitlementSource,
    val accessStatus: SubscriptionAccessStatus,
    val renewalStatus: SubscriptionRenewalStatus,
    val productId: String?,
    val startedAt: Instant?,
    val expiresAt: Instant?,
    val willRenew: Boolean,
    val pendingChange: String?,
    val synchronizedAt: Instant,
    val quota: BillingQuotaStatus,
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
    val originalAppUserId: String?,
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
    val expirationReason: String?,
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
    val latestEventType: InvoiceEventType? = null,
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

data class AdminQuotaAdjustment(
    val userId: Long,
    val ledgerEventId: String,
    val periodStartedAt: Instant,
    val resetAt: Instant,
    val bonusDelta: Int,
    val bonusLimit: Int,
    val reason: String,
    val occurredAt: Instant,
)

data class AdminBillingReconcileRequest(
    val userId: Long,
    val eventId: String,
    val queuedAt: Instant,
)

data class AdminBillingTimelineEntry(
    val category: String,
    val eventId: String,
    val eventType: String,
    val status: String?,
    val reason: String?,
    val occurredAt: Instant,
)

data class AdminUserBillingTimeline(
    val userId: Long,
    val entitlement: BillingEntitlementProjection?,
    val entries: List<AdminBillingTimelineEntry>,
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
    val expiredCheckouts: Int,
    val claimed: Int,
    val completed: Int,
    val retried: Int,
    val compensationRequired: Int,
)

data class SubscriptionReconciliationClaim(
    val subscriptionId: Long,
    val userId: Long,
    val originalTransactionId: String,
    val appAccountToken: UUID,
    val attempt: Int,
)

data class RevenueCatCustomerSnapshot(
    val accessStatus: SubscriptionAccessStatus,
    val renewalStatus: SubscriptionRenewalStatus,
    val expiresAt: Instant?,
    val fetchedAt: Instant,
)
