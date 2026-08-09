package com.buddystudy.billing.domain.entity

import com.buddystudy.billing.domain.BillingActionStatus
import com.buddystudy.billing.domain.BillingActionType
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.BillingJobStatus
import com.buddystudy.billing.domain.BillingJobType
import com.buddystudy.billing.domain.BillingPeriod
import com.buddystudy.billing.domain.BillingProductType
import com.buddystudy.billing.domain.BillingProvider
import com.buddystudy.billing.domain.BillingReceiptStatus
import com.buddystudy.billing.domain.InvoiceEventType
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.InvoiceType
import com.buddystudy.billing.domain.PaymentHistoryEventType
import com.buddystudy.billing.domain.PaymentStatus
import com.buddystudy.billing.domain.QuotaAnchorType
import com.buddystudy.billing.domain.QuotaHistoryType
import com.buddystudy.billing.domain.QuotaReservationStatus
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("user_quota")
class UserQuotaEntity(
    @Id var userId: Long = 0,
    var tierCode: String = "TIER1",
    var anchorType: QuotaAnchorType = QuotaAnchorType.ACCOUNT_CREATED,
    var anchorAt: Instant = Instant.EPOCH,
    var anchorDay: Int = 1,
    var firstPaidAt: Instant? = null,
    var periodStartedAt: Instant = Instant.EPOCH,
    var periodEndsAt: Instant = Instant.EPOCH,
    var baseLimit: Int = 30,
    var bonusLimit: Int = 0,
    var committedCount: Int = 0,
    var reservedCount: Int = 0,
    @ReadOnlyProperty var remainingCount: Int = 30,
    var policyVersion: Int = 5,
    var version: Long = 0,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Table("user_quota_history")
class UserQuotaHistoryEntity(
    @Id var id: Long = 0,
    var eventId: String = "",
    var userId: Long = 0,
    var reservationId: Long? = null,
    var eventType: QuotaHistoryType = QuotaHistoryType.QUOTA_CREATED,
    var affectedPeriodStartedAt: Instant = Instant.EPOCH,
    var affectedPeriodEndsAt: Instant = Instant.EPOCH,
    var appliedToCurrent: Boolean = true,
    var tierCodeBefore: String? = null,
    var tierCodeAfter: String? = null,
    var baseLimitBefore: Int? = null,
    var baseLimitAfter: Int? = null,
    var bonusLimitBefore: Int? = null,
    var bonusLimitAfter: Int? = null,
    var committedCountBefore: Int? = null,
    var committedCountAfter: Int? = null,
    var reservedCountBefore: Int? = null,
    var reservedCountAfter: Int? = null,
    var committedDelta: Int = 0,
    var reservedDelta: Int = 0,
    var bonusDelta: Int = 0,
    var reason: String? = null,
    var actorUserId: Long? = null,
    var quotaVersionAfter: Long? = null,
    var occurredAt: Instant = Instant.EPOCH,
    var createdAt: Instant = Instant.EPOCH,
)

@Table("quota_reservations")
class QuotaReservationEntity(
    @Id var id: Long = 0,
    var reservationKey: String = "",
    var correlationId: String = "",
    var userId: Long = 0,
    var quotaPeriodId: Long? = null,
    var periodStartedAt: Instant = Instant.EPOCH,
    var periodEndsAt: Instant = Instant.EPOCH,
    var status: QuotaReservationStatus = QuotaReservationStatus.RESERVED,
    var reservedAt: Instant = Instant.EPOCH,
    var committedAt: Instant? = null,
    var releasedAt: Instant? = null,
    var releaseReason: String? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Table("membership_tier_products")
class MembershipTierProductEntity(
    @Id var id: Long = 0,
    var tierCode: String = "",
    var provider: BillingProvider = BillingProvider.APPLE,
    var productId: String = "",
    var productType: BillingProductType = BillingProductType.AUTO_RENEWABLE_SUBSCRIPTION,
    var billingPeriod: BillingPeriod? = null,
    var enabled: Boolean = true,
    var sortOrder: Int = 0,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Table("apple_billing_accounts")
class AppleBillingAccountEntity(
    @Id var id: Long = 0,
    var userId: Long = 0,
    var appAccountToken: UUID = UUID(0, 0),
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Table("invoices")
class InvoiceEntity(
    @Id var id: Long = 0,
    var invoiceNumber: UUID = UUID(0, 0),
    var type: InvoiceType = InvoiceType.NORMAL,
    var originalInvoiceId: Long? = null,
    var userId: Long = 0,
    var tierCode: String = "",
    var provider: BillingProvider = BillingProvider.APPLE,
    var productId: String = "",
    var appAccountToken: UUID = UUID(0, 0),
    var currency: String? = null,
    var subtotalMilliunits: Long? = null,
    var taxMilliunits: Long? = null,
    var totalMilliunits: Long? = null,
    var status: InvoiceStatus = InvoiceStatus.WAITING,
    var version: Long = 0,
    var latestEventSequence: Long = 0,
    var paidAt: Instant? = null,
    var fulfilledAt: Instant? = null,
    var cancelledAt: Instant? = null,
    var refundedAt: Instant? = null,
    var expiresAt: Instant? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Table("invoice_events")
class InvoiceEventEntity(
    @Id var id: Long = 0,
    var invoiceId: Long = 0,
    var eventId: String = "",
    var sequenceNumber: Long = 0,
    var eventType: InvoiceEventType = InvoiceEventType.INVOICE_CREATED,
    var source: BillingEventSource = BillingEventSource.SYSTEM,
    var fromStatus: InvoiceStatus? = null,
    var toStatus: InvoiceStatus = InvoiceStatus.WAITING,
    var correlationId: String? = null,
    var causationId: String? = null,
    var actorUserId: Long? = null,
    var reason: String? = null,
    @Column("metadata_json") var metadataJson: String? = null,
    var occurredAt: Instant = Instant.EPOCH,
    var createdAt: Instant = Instant.EPOCH,
)

@Table("payments")
class PaymentEntity(
    @Id var id: Long = 0,
    var invoiceId: Long = 0,
    var userId: Long = 0,
    var provider: BillingProvider = BillingProvider.APPLE,
    var providerTransactionId: String = "",
    var providerOriginalTransactionId: String = "",
    var appTransactionId: String? = null,
    var webOrderLineItemId: String? = null,
    var appAccountToken: UUID = UUID(0, 0),
    var productId: String = "",
    var productType: BillingProductType = BillingProductType.AUTO_RENEWABLE_SUBSCRIPTION,
    var environment: BillingEnvironment = BillingEnvironment.SANDBOX,
    var quantity: Int = 1,
    var priceMilliunits: Long? = null,
    var currency: String? = null,
    var status: PaymentStatus = PaymentStatus.VERIFIED,
    var purchaseAt: Instant = Instant.EPOCH,
    var originalPurchaseAt: Instant? = null,
    var expiresAt: Instant? = null,
    var revocationAt: Instant? = null,
    var revocationReason: Int? = null,
    var signedAt: Instant = Instant.EPOCH,
    var verifiedAt: Instant = Instant.EPOCH,
    var signedPayloadSha256: String = "",
    var version: Long = 0,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Table("payments_history")
class PaymentHistoryEntity(
    @Id var id: Long = 0,
    var paymentId: Long = 0,
    var invoiceId: Long = 0,
    var eventId: String = "",
    var eventType: PaymentHistoryEventType = PaymentHistoryEventType.VERIFIED,
    var source: BillingEventSource = BillingEventSource.SYSTEM,
    var fromStatus: PaymentStatus? = null,
    var toStatus: PaymentStatus = PaymentStatus.VERIFIED,
    @Column("provider_notification_uuid") var providerNotificationUuid: String? = null,
    var reason: String? = null,
    @Column("metadata_json") var metadataJson: String? = null,
    var occurredAt: Instant = Instant.EPOCH,
    var createdAt: Instant = Instant.EPOCH,
)

@Table("billing_actions")
class BillingActionEntity(
    @Id var id: Long = 0,
    var actionId: UUID = UUID(0, 0),
    var idempotencyKey: String = "",
    var invoiceId: Long = 0,
    var paymentId: Long = 0,
    var userId: Long = 0,
    var actionType: BillingActionType = BillingActionType.REFUND,
    var status: BillingActionStatus = BillingActionStatus.REQUIRED,
    var reason: String? = null,
    @Column("provider_notification_uuid") var providerNotificationUuid: String? = null,
    var requestedAt: Instant = Instant.EPOCH,
    var completedAt: Instant? = null,
    var failedAt: Instant? = null,
    var lastError: String? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Table("billing_fulfillment_outbox")
class BillingJobEntity(
    @Id var id: Long = 0,
    var jobId: UUID = UUID(0, 0),
    var invoiceId: Long = 0,
    var paymentId: Long = 0,
    var jobType: BillingJobType = BillingJobType.FULFILLMENT,
    var status: BillingJobStatus = BillingJobStatus.PENDING,
    var attempts: Int = 0,
    var maxAttempts: Int = 3,
    var nextAttemptAt: Instant = Instant.EPOCH,
    var claimedAt: Instant? = null,
    var claimToken: UUID? = null,
    var lastError: String? = null,
    var completedAt: Instant? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Table("billing_apple_notification_inbox")
class AppleBillingNotificationEntity(
    @Id var id: Long = 0,
    @Column("notification_uuid") var notificationUuid: String = "",
    var notificationType: String = "",
    var subtype: String? = null,
    var environment: BillingEnvironment = BillingEnvironment.SANDBOX,
    var signedPayloadSha256: String = "",
    var transactionId: String? = null,
    var processingStatus: BillingReceiptStatus = BillingReceiptStatus.RECEIVED,
    var processedAt: Instant? = null,
    var lastError: String? = null,
    var receivedAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Table("billing_revenuecat_event_inbox")
class RevenueCatBillingEventEntity(
    @Id var id: Long = 0,
    var eventId: String = "",
    var eventType: String = "",
    var appUserId: String? = null,
    var originalAppUserId: String? = null,
    var store: String? = null,
    var productId: String? = null,
    var transactionId: String? = null,
    var environment: BillingEnvironment? = null,
    var cancelReason: String? = null,
    var expirationReason: String? = null,
    var signedPayloadSha256: String = "",
    var processingStatus: BillingReceiptStatus = BillingReceiptStatus.RECEIVED,
    var eventAt: Instant = Instant.EPOCH,
    var receivedAt: Instant = Instant.EPOCH,
    var processedAt: Instant? = null,
    var lastError: String? = null,
    var updatedAt: Instant = Instant.EPOCH,
)
