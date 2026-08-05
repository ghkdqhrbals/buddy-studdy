package com.buddystudy.backend.billing

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.billing.application.model.ApplyAppleNotificationCommand
import com.buddystudy.backend.billing.application.model.AdminBillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.AdminBillingInvoicePage
import com.buddystudy.backend.billing.application.model.BillingAction
import com.buddystudy.backend.billing.application.model.BillingCatalog
import com.buddystudy.backend.billing.application.model.BillingClientAction
import com.buddystudy.backend.billing.application.model.BillingFulfillmentJobClaim
import com.buddystudy.backend.billing.application.model.BillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.BillingInvoicePage
import com.buddystudy.backend.billing.application.model.BillingInvoiceSummary
import com.buddystudy.backend.billing.application.model.BillingTierProduct
import com.buddystudy.backend.billing.application.model.BillingEntitlementProjection
import com.buddystudy.backend.billing.application.model.CreateBillingCheckoutCommand
import com.buddystudy.backend.billing.application.model.RecordVerifiedPaymentCommand
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.SyncAppleTransactionCommand
import com.buddystudy.backend.billing.application.model.VerifiedAppleNotification
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.model.VerifiedRevenueCatEvent
import com.buddystudy.backend.billing.application.port.outbound.AppleBillingVerificationPort
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.billing.application.service.BillingService
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import com.buddystudy.billing.domain.BillingActionStatus
import com.buddystudy.billing.domain.BillingActionType
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.BillingProductType
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.InvoiceType
import com.buddystudy.billing.domain.PaymentStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class BillingServiceTest {
    private val now = Instant.parse("2026-08-03T00:00:00Z")
    private val token = UUID.fromString("3f0c5f50-6521-4ba0-a990-73500e915f57")
    private val invoiceNumber = UUID.fromString("2306d81d-1323-48c4-bb2b-a40cc48f70da")
    private val transaction = VerifiedAppleTransaction(
        transactionId = "200000000000001",
        originalTransactionId = "200000000000000",
        appTransactionId = null,
        webOrderLineItemId = null,
        appAccountToken = token,
        productId = "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
        productType = BillingProductType.AUTO_RENEWABLE_SUBSCRIPTION,
        environment = BillingEnvironment.SANDBOX,
        quantity = 1,
        priceMilliunits = 4_900_000,
        currency = "KRW",
        purchaseAt = now.minusSeconds(10),
        originalPurchaseAt = now.minusSeconds(10),
        expiresAt = now.plusSeconds(2_592_000),
        revocationAt = null,
        revocationReason = null,
        signedAt = now,
        signedPayloadSha256 = "a".repeat(64),
    )
    private val product = BillingTierProduct(
        tierCode = "TIER2",
        description = "Extended",
        monthlyQuestionLimit = 300,
        productId = transaction.productId,
        productType = transaction.productType,
        billingPeriod = "P1M",
        sortOrder = 20,
    )

    @Test
    fun `anonymous accounts cannot receive an app account token`() {
        val ledger = FakeLedger(token, product)
        val service = service(ledger)

        val error = assertThrows(ApiRuntimeException::class.java) {
            runBlocking { service.catalog(principal(anonymous = true)) }
        }

        assertEquals(ApiErrorCode.BILLING_ACCOUNT_REQUIRED, error.errorCode)
        assertEquals(0, ledger.tokenReads)
    }

    @Test
    fun `verified transaction token must belong to current user`() {
        val ledger = FakeLedger(UUID.randomUUID(), product)
        val service = service(ledger)

        val error = assertThrows(ApiRuntimeException::class.java) {
            runBlocking { service.syncAppleTransaction(principal(), syncCommand()) }
        }

        assertEquals(ApiErrorCode.BILLING_TRANSACTION_CONFLICT, error.errorCode)
        assertEquals(0, ledger.recordedPayments.size)
    }

    @Test
    fun `checkout creates pending invoice before StoreKit transaction`() = runBlocking {
        val ledger = FakeLedger(token, product)
        val service = service(ledger)

        val result = service.createCheckout(
            principal(),
            CreateBillingCheckoutCommand(product.productId, "checkout-request-1"),
        )

        assertEquals(InvoiceStatus.WAITING, result.status)
        assertEquals(listOf("checkout-request-1"), ledger.pendingCheckoutKeys)
        assertTrue(ledger.recordedPayments.isEmpty())
    }

    @Test
    fun `disabled or unknown tier products are rejected before ledger write`() {
        val ledger = FakeLedger(token, null)
        val service = service(ledger)

        val error = assertThrows(ApiRuntimeException::class.java) {
            runBlocking { service.syncAppleTransaction(principal(), syncCommand()) }
        }

        assertEquals(ApiErrorCode.BILLING_TRANSACTION_INVALID, error.errorCode)
        assertTrue(ledger.recordedPayments.isEmpty())
    }

    @Test
    fun `membership fulfillment failure leaves verified payment for durable recovery`() {
        val ledger = FakeLedger(token, product).apply { fulfillmentError = IllegalStateException("membership rollback") }
        val service = service(ledger)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.syncAppleTransaction(principal(), syncCommand()) }
        }

        assertEquals(1, ledger.recordedPayments.size)
        assertTrue(ledger.compensatedInvoices.isEmpty())
    }

    @Test
    fun `recovery completes a fulfillment abandoned by a dead backend process`() = runBlocking {
        val claim = BillingFulfillmentJobClaim(10, 99, 0, 3, UUID.randomUUID())
        val ledger = FakeLedger(token, product).apply { fulfillmentClaims += claim }
        val service = service(ledger)

        val result = service.recoverDueFulfillments()

        assertEquals(1, result.claimed)
        assertEquals(1, result.completed)
        assertEquals(InvoiceStatus.COMPLETED, ledger.lastFulfillmentStatus)
    }

    @Test
    fun `recovery expires unpaid checkouts after ten minutes`() = runBlocking {
        val ledger = FakeLedger(token, product).apply { expiredCheckoutCount = 3 }
        val service = service(ledger)

        val result = service.recoverDueFulfillments()

        assertEquals(3, result.expiredCheckouts)
        assertEquals(now.minusSeconds(600), ledger.checkoutExpirationCutoff)
        assertEquals(now, ledger.checkoutExpirationRunAt)
        assertEquals(100, ledger.checkoutExpirationLimit)
    }

    @Test
    fun `recovery retries transient errors and requires compensation only after max attempts`() = runBlocking {
        val retryClaim = BillingFulfillmentJobClaim(10, 99, 0, 3, UUID.randomUUID())
        val finalClaim = BillingFulfillmentJobClaim(11, 100, 2, 3, UUID.randomUUID())
        val ledger = FakeLedger(token, product).apply {
            fulfillmentClaims += listOf(retryClaim, finalClaim)
            fulfillmentError = IllegalStateException("membership unavailable")
        }
        val service = service(ledger)

        val result = service.recoverDueFulfillments()

        assertEquals(1, result.retried)
        assertEquals(1, result.compensationRequired)
        assertEquals(listOf(retryClaim), ledger.rescheduledClaims)
        assertEquals(listOf(100L), ledger.compensatedInvoices)
    }

    @Test
    fun `valid transaction is recorded and fulfilled`() = runBlocking {
        val ledger = FakeLedger(token, product)
        val service = service(ledger)

        val result = service.syncAppleTransaction(principal(), syncCommand())

        assertEquals(InvoiceStatus.COMPLETED, result.status)
        assertEquals(1, ledger.recordedPayments.size)
        assertEquals(invoiceNumber, ledger.recordedPayments.single().invoiceNumber)
        assertEquals(BillingEventSource.CLIENT, ledger.recordedPayments.single().source)
    }

    @Test
    fun `notification processing failure remains recorded after lifecycle rollback`() {
        val notification = notification()
        val ledger = FakeLedger(token, product).apply {
            notificationApplyError = IllegalStateException("provider lifecycle rollback")
        }
        val service = service(ledger, notification)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.receive(syncCommand().signedTransaction) }
        }

        assertEquals(listOf(notification.notificationUUID), ledger.recordedNotifications)
        assertEquals(listOf(notification.notificationUUID), ledger.failedNotifications)
        assertEquals(BillingEventSource.APPLE_NOTIFICATION, ledger.recordedPayments.single().source)
        assertEquals(null, ledger.recordedPayments.single().invoiceNumber)
    }

    @Test
    fun `duplicate Apple notification stops before payment or lifecycle work`() = runBlocking {
        val notification = notification()
        val ledger = FakeLedger(token, product).apply { notificationIsNew = false }
        val service = service(ledger, notification)

        service.receive(syncCommand().signedTransaction)

        assertTrue(ledger.recordedPayments.isEmpty())
        assertEquals(0, ledger.appliedNotifications)
    }

    private fun service(
        ledger: FakeLedger,
        notification: VerifiedAppleNotification? = null,
    ) = BillingService(
        verifier = object : AppleBillingVerificationPort {
            override suspend fun verifyTransaction(
                signedTransaction: String,
                environment: BillingEnvironment,
            ): VerifiedAppleTransaction = transaction

            override suspend fun verifyNotification(signedPayload: String): VerifiedAppleNotification =
                notification ?: error("not used")
        },
        ledger = ledger,
        memberships = org.mockito.Mockito.mock(QuestionMembershipPort::class.java),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun principal(anonymous: Boolean = false) = Principal(733, "device", 1, anonymous, if (anonymous) "ANONYMOUS" else "ACTIVE")
    private fun syncCommand() = SyncAppleTransactionCommand(
        "${"a".repeat(32)}.${"b".repeat(32)}.${"c".repeat(32)}",
        BillingEnvironment.SANDBOX,
        invoiceNumber,
    )
    private fun notification() = VerifiedAppleNotification(
        notificationUUID = "notification-1",
        notificationType = "DID_CHANGE_RENEWAL_STATUS",
        subtype = "AUTO_RENEW_DISABLED",
        environment = BillingEnvironment.SANDBOX,
        signedAt = now,
        signedPayloadSha256 = "b".repeat(64),
        transaction = transaction,
    )

    private class FakeLedger(
        private val token: UUID,
        private val product: BillingTierProduct?,
    ) : BillingLedgerPort {
        override suspend fun entitlementForUser(userId: Long): BillingEntitlementProjection? = null
        var tokenReads = 0
        var fulfillmentError: Exception? = null
        var notificationApplyError: Exception? = null
        var notificationIsNew = true
        val recordedPayments = mutableListOf<RecordVerifiedPaymentCommand>()
        val pendingCheckoutKeys = mutableListOf<String>()
        val compensatedInvoices = mutableListOf<Long>()
        val fulfillmentClaims = mutableListOf<BillingFulfillmentJobClaim>()
        val rescheduledClaims = mutableListOf<BillingFulfillmentJobClaim>()
        val recordedNotifications = mutableListOf<String>()
        val failedNotifications = mutableListOf<String>()
        var appliedNotifications = 0
        var lastFulfillmentStatus: InvoiceStatus? = null
        var expiredCheckoutCount = 0
        var checkoutExpirationCutoff: Instant? = null
        var checkoutExpirationRunAt: Instant? = null
        var checkoutExpirationLimit: Int? = null

        override suspend fun findOrCreateAppAccountToken(userId: Long, now: Instant): UUID {
            tokenReads += 1
            return token
        }
        override suspend fun userIdForAppAccountToken(appAccountToken: UUID): Long? = 733
        override suspend fun enabledTierProducts(): List<BillingTierProduct> = listOfNotNull(product)
        override suspend fun enabledTierProduct(productId: String): BillingTierProduct? = product?.takeIf { it.productId == productId }
        override suspend fun createPendingInvoice(
            userId: Long,
            appAccountToken: UUID,
            tierProduct: BillingTierProduct,
            idempotencyKey: String,
            now: Instant,
        ): BillingInvoiceSummary {
            pendingCheckoutKeys += idempotencyKey
            return invoice(InvoiceStatus.WAITING)
        }
        override suspend fun abandonPendingInvoice(
            userId: Long,
            invoiceNumber: UUID,
            now: Instant,
        ): BillingInvoiceSummary = invoice(InvoiceStatus.FAILED)
        override suspend fun expirePendingCheckouts(expiredBefore: Instant, now: Instant, limit: Int): Int {
            checkoutExpirationCutoff = expiredBefore
            checkoutExpirationRunAt = now
            checkoutExpirationLimit = limit
            return expiredCheckoutCount
        }
        override suspend fun recordVerifiedPayment(command: RecordVerifiedPaymentCommand): BillingInvoiceSummary {
            recordedPayments += command
            return invoice(InvoiceStatus.WAITING)
        }
        override suspend fun fulfill(invoiceId: Long, now: Instant): BillingInvoiceSummary {
            fulfillmentError?.let { throw it }
            lastFulfillmentStatus = InvoiceStatus.COMPLETED
            return invoice(InvoiceStatus.COMPLETED)
        }
        override suspend fun requireCompensation(invoiceId: Long, reason: String, now: Instant): BillingInvoiceSummary {
            compensatedInvoices += invoiceId
            return invoice(InvoiceStatus.FAILED)
        }
        override suspend fun claimDueFulfillmentJobs(
            now: Instant,
            staleBefore: Instant,
            limit: Int,
        ): List<BillingFulfillmentJobClaim> = fulfillmentClaims.take(limit)
        override suspend fun rescheduleFulfillmentJob(
            claim: BillingFulfillmentJobClaim,
            error: String,
            nextAttemptAt: Instant,
            now: Instant,
        ) {
            rescheduledClaims += claim
        }
        override suspend fun invoice(userId: Long, invoiceId: Long): BillingInvoiceDetail? = null
        override suspend fun invoices(userId: Long, limit: Int, offset: Int) = BillingInvoicePage(limit, offset, emptyList())
        override suspend fun paymentOwner(paymentId: Long): Long? = 733
        override suspend fun requestRefund(
            userId: Long, paymentId: Long, command: RequestBillingActionCommand, now: Instant,
        ): BillingAction = action(BillingActionType.REFUND)
        override suspend fun requestCancellation(
            userId: Long, originalTransactionId: String, command: RequestBillingActionCommand, now: Instant,
        ): BillingAction = action(BillingActionType.CANCELLATION)
        override suspend fun recordAppleNotification(notification: VerifiedAppleNotification, now: Instant): Boolean {
            recordedNotifications += notification.notificationUUID
            return notificationIsNew
        }
        override suspend fun applyAppleNotification(command: ApplyAppleNotificationCommand): Boolean {
            notificationApplyError?.let { throw it }
            appliedNotifications += 1
            return true
        }
        override suspend fun markAppleNotificationFailed(notificationUUID: String, error: String, now: Instant) {
            failedNotifications += notificationUUID
        }
        override suspend fun recordRevenueCatEvent(event: VerifiedRevenueCatEvent, now: Instant): Boolean = true
        override suspend fun applyRevenueCatEvent(event: VerifiedRevenueCatEvent, now: Instant): Boolean = true
        override suspend fun markRevenueCatEventFailed(eventId: String, error: String, now: Instant) = Unit
        override suspend fun adminInvoices(
            query: String?, status: String?, limit: Int, offset: Int,
        ): AdminBillingInvoicePage = AdminBillingInvoicePage(limit, offset, 0, emptyList())
        override suspend fun adminInvoice(invoiceId: Long): AdminBillingInvoiceDetail? = null
        override suspend fun adminRequestRefund(
            invoiceId: Long, command: RequestBillingActionCommand, now: Instant,
        ): BillingAction = action(BillingActionType.REFUND)
        override suspend fun adminRequestCancellation(
            invoiceId: Long, command: RequestBillingActionCommand, now: Instant,
        ): BillingAction = action(BillingActionType.CANCELLATION)

        private fun invoice(status: InvoiceStatus) = BillingInvoiceSummary(
            id = 99,
            invoiceNumber = UUID.fromString("2306d81d-1323-48c4-bb2b-a40cc48f70da"),
            type = InvoiceType.NORMAL,
            originalInvoiceId = null,
            tierCode = "TIER2",
            productId = "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
            status = status,
            version = 2,
            paymentId = 88,
            transactionId = "200000000000001",
            originalTransactionId = "200000000000000",
            paymentStatus = PaymentStatus.VERIFIED,
            priceMilliunits = 4_900_000,
            currency = "KRW",
            purchaseAt = Instant.parse("2026-08-02T23:59:50Z"),
            expiresAt = Instant.parse("2026-09-02T00:00:00Z"),
            createdAt = Instant.parse("2026-08-03T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-03T00:00:00Z"),
        )

        private fun action(type: BillingActionType) = BillingAction(
            UUID.randomUUID(), type, BillingActionStatus.AWAITING_APPLE, 99, 88,
            "200000000000001", "200000000000000", null, Instant.now(), null,
            if (type == BillingActionType.REFUND) BillingClientAction.BEGIN_APPLE_REFUND_REQUEST
            else BillingClientAction.OPEN_APPLE_SUBSCRIPTION_MANAGEMENT,
        )
    }
}
