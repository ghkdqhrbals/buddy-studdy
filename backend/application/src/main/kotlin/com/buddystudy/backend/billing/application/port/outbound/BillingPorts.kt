package com.buddystudy.backend.billing.application.port.outbound

import com.buddystudy.backend.billing.application.model.ApplyAppleNotificationCommand
import com.buddystudy.backend.billing.application.model.AdminBillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.AdminBillingInvoicePage
import com.buddystudy.backend.billing.application.model.AdminQuotaAdjustment
import com.buddystudy.backend.billing.application.model.AdminBillingReconcileRequest
import com.buddystudy.backend.billing.application.model.AdminUserBillingTimeline
import com.buddystudy.backend.billing.application.model.SubscriptionReconciliationClaim
import com.buddystudy.backend.billing.application.model.RevenueCatCustomerSnapshot
import com.buddystudy.backend.billing.application.model.BillingAction
import com.buddystudy.backend.billing.application.model.BillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.BillingInvoicePage
import com.buddystudy.backend.billing.application.model.BillingInvoiceSummary
import com.buddystudy.backend.billing.application.model.BillingEntitlementProjection
import com.buddystudy.backend.billing.application.model.BillingTierProduct
import com.buddystudy.backend.billing.application.model.BillingFulfillmentJobClaim
import com.buddystudy.backend.billing.application.model.RecordVerifiedPaymentCommand
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.VerifiedAppleNotification
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.model.RevenueCatWebhookRequest
import com.buddystudy.backend.billing.application.model.VerifiedRevenueCatEvent
import com.buddystudy.billing.domain.BillingEnvironment
import java.time.Instant
import java.util.UUID

interface AppleBillingVerificationPort {
    suspend fun verifyTransaction(signedTransaction: String, environment: BillingEnvironment): VerifiedAppleTransaction
    suspend fun verifyNotification(signedPayload: String): VerifiedAppleNotification
}

interface RevenueCatWebhookVerificationPort {
    suspend fun verify(request: RevenueCatWebhookRequest): VerifiedRevenueCatEvent
}

interface RevenueCatCustomerInfoPort {
    suspend fun fetch(appAccountToken: UUID, originalTransactionId: String): RevenueCatCustomerSnapshot
}

interface RevenueCatTransactionVerificationPort {
    suspend fun verify(transactionId: String): VerifiedAppleTransaction
}

interface BillingLedgerPort {
    suspend fun findOrCreateAppAccountToken(userId: Long, now: Instant): UUID
    suspend fun userIdForAppAccountToken(appAccountToken: UUID): Long?
    suspend fun enabledTierProducts(): List<BillingTierProduct>
    suspend fun enabledTierProduct(productId: String): BillingTierProduct?
    /** Resolves disabled legacy products so renewals and refunds remain processable. */
    suspend fun tierProduct(productId: String): BillingTierProduct?
    suspend fun entitlementForUser(userId: Long): BillingEntitlementProjection?

    /** Creates the event-sourced NORMAL/WAITING invoice before StoreKit is opened. */
    suspend fun createPendingInvoice(
        userId: Long,
        appAccountToken: UUID,
        tierProduct: BillingTierProduct,
        idempotencyKey: String,
        now: Instant,
    ): BillingInvoiceSummary

    /** Idempotently closes a checkout when StoreKit reports userCancelled before a transaction exists. */
    suspend fun abandonPendingInvoice(userId: Long, invoiceNumber: UUID, now: Instant): BillingInvoiceSummary

    /** Atomically expires unpaid NORMAL/WAITING checkouts created at or before the cutoff. */
    suspend fun expirePendingCheckouts(expiredBefore: Instant, now: Instant, limit: Int): Int

    /** Atomically attaches a verified payment to a PENDING invoice and creates fulfillment work. */
    suspend fun recordVerifiedPayment(command: RecordVerifiedPaymentCommand): BillingInvoiceSummary

    /** Separate transaction boundary: grants the tier and settles the invoice/payment projections. */
    suspend fun fulfill(invoiceId: Long, now: Instant): BillingInvoiceSummary

    /** REQUIRES_NEW boundary used after fulfillment rollback; never marks an Apple refund as completed. */
    suspend fun requireCompensation(invoiceId: Long, reason: String, now: Instant): BillingInvoiceSummary

    /** Claims pending or abandoned fulfillment work so a process crash cannot strand a verified charge. */
    suspend fun claimDueFulfillmentJobs(
        now: Instant,
        staleBefore: Instant,
        limit: Int,
    ): List<BillingFulfillmentJobClaim>

    /** Releases a claimed attempt with bounded backoff after a recoverable fulfillment failure. */
    suspend fun rescheduleFulfillmentJob(
        claim: BillingFulfillmentJobClaim,
        error: String,
        nextAttemptAt: Instant,
        now: Instant,
    )

    suspend fun invoice(userId: Long, invoiceId: Long): BillingInvoiceDetail?
    suspend fun invoices(userId: Long, limit: Int, offset: Int): BillingInvoicePage
    suspend fun paymentOwner(paymentId: Long): Long?

    suspend fun requestRefund(
        userId: Long,
        paymentId: Long,
        command: RequestBillingActionCommand,
        now: Instant,
    ): BillingAction

    suspend fun requestCancellation(
        userId: Long,
        originalTransactionId: String,
        command: RequestBillingActionCommand,
        now: Instant,
    ): BillingAction

    /** REQUIRES_NEW boundary: persists receipt before any lifecycle processing can fail. */
    suspend fun recordAppleNotification(notification: VerifiedAppleNotification, now: Instant): Boolean

    /** Applies an already recorded notification and marks it processed or ignored atomically. */
    suspend fun applyAppleNotification(command: ApplyAppleNotificationCommand): Boolean

    /** REQUIRES_NEW boundary: preserves processing failure details after the apply transaction rolls back. */
    suspend fun markAppleNotificationFailed(notificationUUID: String, error: String, now: Instant)

    /** REQUIRES_NEW receipt used to deduplicate RevenueCat's at-least-once webhook delivery. */
    suspend fun recordRevenueCatEvent(event: VerifiedRevenueCatEvent, now: Instant): Boolean

    /** Claims verified RevenueCat receipts after the webhook transaction has committed. */
    suspend fun claimDueRevenueCatEvents(now: Instant, limit: Int): List<VerifiedRevenueCatEvent> = emptyList()

    /** Applies a verified RevenueCat lifecycle event and completes its receipt atomically. */
    suspend fun applyRevenueCatEvent(event: VerifiedRevenueCatEvent, now: Instant): Boolean

    /** REQUIRES_NEW failure update so processing errors survive transaction rollback. */
    suspend fun markRevenueCatEventFailed(eventId: String, error: String, now: Instant)

    suspend fun adminInvoices(query: String?, status: String?, limit: Int, offset: Int): AdminBillingInvoicePage
    suspend fun adminInvoice(invoiceId: Long): AdminBillingInvoiceDetail?
    suspend fun adminRequestRefund(invoiceId: Long, command: RequestBillingActionCommand, now: Instant): BillingAction
    suspend fun adminRequestCancellation(invoiceId: Long, command: RequestBillingActionCommand, now: Instant): BillingAction
    suspend fun adminAdjustQuota(
        userId: Long,
        bonusDelta: Int,
        reason: String,
        idempotencyKey: String,
        now: Instant,
    ): AdminQuotaAdjustment = error("Quota adjustment is not supported by this billing ledger.")
    suspend fun adminRequestReconcile(
        userId: Long,
        reason: String?,
        now: Instant,
    ): AdminBillingReconcileRequest = error("Billing reconciliation is not supported by this billing ledger.")
    suspend fun adminUserTimeline(userId: Long, limit: Int): AdminUserBillingTimeline =
        error("Billing timeline is not supported by this billing ledger.")
    suspend fun claimDueSubscriptionReconciliations(now: Instant, limit: Int): List<SubscriptionReconciliationClaim> = emptyList()
    suspend fun applySubscriptionSnapshot(
        claim: SubscriptionReconciliationClaim,
        snapshot: RevenueCatCustomerSnapshot,
        now: Instant,
    ) = Unit
    suspend fun recordSubscriptionReconcileFailure(
        claim: SubscriptionReconciliationClaim,
        error: String,
        now: Instant,
    ) = Unit
}
