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
import com.buddystudy.backend.billing.application.model.BillingInvoicePhase
import com.buddystudy.backend.billing.application.model.BillingInvoiceSummary
import com.buddystudy.backend.billing.application.model.BillingTierProduct
import com.buddystudy.backend.billing.application.model.BillingEntitlementProjection
import com.buddystudy.backend.billing.application.model.CreateBillingCheckoutCommand
import com.buddystudy.backend.billing.application.model.ConfirmRevenueCatTransactionCommand
import com.buddystudy.backend.billing.application.model.RecordVerifiedPaymentCommand
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.SyncAppleTransactionCommand
import com.buddystudy.backend.billing.application.model.VerifiedAppleNotification
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.model.VerifiedRevenueCatEvent
import com.buddystudy.backend.billing.application.port.outbound.AppleBillingVerificationPort
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatTransactionVerificationPort
import com.buddystudy.backend.billing.application.service.BillingService
import com.buddystudy.backend.billing.application.service.VerifiedBillingPaymentService
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPlan
import com.buddystudy.backend.study.application.port.outbound.QuestionQuotaStatus
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import com.buddystudy.billing.domain.BillingActionStatus
import com.buddystudy.billing.domain.BillingActionType
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.BillingProductType
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.InvoiceType
import com.buddystudy.billing.domain.PaymentStatus
import com.buddystudy.billing.domain.EntitlementSource
import com.buddystudy.billing.domain.SubscriptionAccessStatus
import com.buddystudy.billing.domain.SubscriptionRenewalStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
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
    fun `billing status exposes the exact scheduled plan transition`() = runBlocking {
        val changesAt = Instant.parse("2026-09-02T00:00:00Z")
        val ledger = FakeLedger(token, product).apply {
            projectedEntitlement = BillingEntitlementProjection(
                tierCode = "TIER3",
                source = EntitlementSource.APP_STORE,
                accessStatus = SubscriptionAccessStatus.ACTIVE,
                renewalStatus = SubscriptionRenewalStatus.WILL_RENEW,
                productId = "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                startedAt = Instant.parse("2026-08-02T00:00:00Z"),
                expiresAt = changesAt,
                willRenew = true,
                pendingProductId = product.productId,
                synchronizedAt = now,
            )
        }

        val status = service(ledger).status(principal())

        assertEquals("TIER3", status.planTransition?.currentTierCode)
        assertEquals("TIER2", status.planTransition?.nextTierCode)
        assertEquals(changesAt, status.planTransition?.currentPlanEndsAt)
        assertEquals(changesAt, status.planTransition?.nextPlanStartsAt)
    }

    @Test
    fun `pending upgrade is not exposed as a future plan transition`() = runBlocking {
        val changesAt = Instant.parse("2026-09-02T00:00:00Z")
        val tier2ProductId = product.productId
        val tier3Product = product.copy(
            tierCode = "TIER3",
            monthlyQuestionLimit = 1_000,
            productId = "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
            sortOrder = 30,
        )
        val ledger = FakeLedger(token, tier3Product).apply {
            projectedEntitlement = projectedEntitlement?.copy(
                tierCode = "TIER2",
                productId = tier2ProductId,
                expiresAt = changesAt,
                pendingProductId = tier3Product.productId,
            )
        }

        val status = service(ledger).status(principal())

        assertEquals(null, status.planTransition)
        assertEquals(null, status.pendingChange)
    }

    @Test
    fun `cancelled subscription status does not expose a future plan change`() = runBlocking {
        val changesAt = Instant.parse("2026-09-02T00:00:00Z")
        val ledger = FakeLedger(token, product).apply {
            projectedEntitlement = projectedEntitlement?.copy(
                renewalStatus = SubscriptionRenewalStatus.CANCELED,
                expiresAt = changesAt,
                willRenew = false,
                pendingProductId = null,
            )
        }

        val status = service(ledger).status(principal())

        assertEquals(null, status.planTransition)
        assertEquals(null, status.pendingChange)
        assertEquals(SubscriptionRenewalStatus.CANCELED, status.renewalStatus)
        assertEquals(changesAt, status.expiresAt)
    }

    @Test
    fun `cancelled subscription ignores a stale pending downgrade`() = runBlocking {
        val changesAt = Instant.parse("2026-09-02T00:00:00Z")
        val ledger = FakeLedger(token, product).apply {
            projectedEntitlement = BillingEntitlementProjection(
                tierCode = "TIER3",
                source = EntitlementSource.APP_STORE,
                accessStatus = SubscriptionAccessStatus.ACTIVE,
                renewalStatus = SubscriptionRenewalStatus.CANCELED,
                productId = "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                startedAt = Instant.parse("2026-08-02T00:00:00Z"),
                expiresAt = changesAt,
                willRenew = false,
                pendingProductId = product.productId,
                synchronizedAt = now,
            )
        }

        val status = service(ledger).status(principal())

        assertEquals(null, status.planTransition)
        assertEquals(null, status.pendingChange)
    }

    @Test
    fun `already effective product change is not exposed as a future transition`() = runBlocking {
        val ledger = FakeLedger(token, product).apply {
            projectedEntitlement = BillingEntitlementProjection(
                tierCode = "TIER3",
                source = EntitlementSource.APP_STORE,
                accessStatus = SubscriptionAccessStatus.GRACE_PERIOD,
                renewalStatus = SubscriptionRenewalStatus.WILL_RENEW,
                productId = "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                startedAt = now.minusSeconds(2_592_000),
                expiresAt = now.minusSeconds(1),
                willRenew = true,
                pendingProductId = product.productId,
                synchronizedAt = now,
            )
        }

        val status = service(ledger).status(principal())

        assertEquals(null, status.planTransition)
        assertEquals(null, status.pendingChange)
    }

    @Test
    fun `expired active projection cannot expose a paid membership`() = runBlocking {
        val ledger = FakeLedger(token, product).apply {
            projectedEntitlement = projectedEntitlement?.copy(
                expiresAt = now.minusSeconds(1),
                accessStatus = SubscriptionAccessStatus.ACTIVE,
            )
        }

        val status = service(ledger, membershipTierCode = "TIER1", monthlyLimit = 30).status(principal())

        assertEquals("TIER1", status.tierCode)
        assertEquals(EntitlementSource.FREE, status.source)
        assertEquals(SubscriptionAccessStatus.ACTIVE, status.accessStatus)
        assertEquals(null, status.productId)
        assertEquals(30, status.quota.baseLimit)
    }

    @Test
    fun `billing retry does not claim that the user will fall back to free`() = runBlocking {
        val ledger = FakeLedger(token, product).apply {
            projectedEntitlement = projectedEntitlement?.copy(
                renewalStatus = SubscriptionRenewalStatus.BILLING_RETRY,
                willRenew = false,
            )
        }

        val status = service(ledger).status(principal())

        assertEquals(null, status.planTransition)
    }

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
        assertEquals(BillingInvoicePhase.PREPARED, result.phase)
        assertEquals(listOf("checkout-request-1"), ledger.pendingCheckoutKeys)
        assertTrue(ledger.recordedPayments.isEmpty())
    }

    @Test
    fun `retired product cannot open checkout but remains valid for transaction recovery`() = runBlocking {
        val ledger = FakeLedger(token, product, productEnabled = false)
        val service = service(ledger)

        val checkoutError = assertThrows(ApiRuntimeException::class.java) {
            runBlocking {
                service.createCheckout(
                    principal(),
                    CreateBillingCheckoutCommand(product.productId, "retired-product-checkout"),
                )
            }
        }
        assertEquals(ApiErrorCode.BILLING_TRANSACTION_INVALID, checkoutError.errorCode)
        assertTrue(ledger.pendingCheckoutKeys.isEmpty())

        val recovered = service.syncAppleTransaction(principal(), syncCommand())
        assertEquals(InvoiceStatus.COMPLETED, recovered.status)
        assertEquals(1, ledger.recordedPayments.size)
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
    fun `membership fulfillment failure leaves verified payment and returns an explicit application failure`() {
        val ledger = FakeLedger(token, product).apply { fulfillmentError = IllegalStateException("membership rollback") }
        val service = service(ledger)

        val error = assertThrows(ApiRuntimeException::class.java) {
            runBlocking { service.syncAppleTransaction(principal(), syncCommand()) }
        }

        assertEquals(ApiErrorCode.BILLING_APPLICATION_FAILED, error.errorCode)
        assertEquals(1, ledger.recordedPayments.size)
        assertTrue(ledger.compensatedInvoices.isEmpty())
    }

    @Test
    fun `transaction sync does not return success when entitlement projection is missing`() {
        val ledger = FakeLedger(token, product).apply { projectedEntitlement = null }
        val service = service(ledger)

        val error = assertThrows(ApiRuntimeException::class.java) {
            runBlocking { service.syncAppleTransaction(principal(), syncCommand()) }
        }

        assertEquals(ApiErrorCode.BILLING_APPLICATION_FAILED, error.errorCode)
        assertEquals(InvoiceStatus.COMPLETED, ledger.lastFulfillmentStatus)
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
        assertEquals(BillingInvoicePhase.FULFILLED, result.phase)
        assertEquals(1, ledger.recordedPayments.size)
        assertEquals(invoiceNumber, ledger.recordedPayments.single().invoiceNumber)
        assertEquals(BillingEventSource.CLIENT, ledger.recordedPayments.single().source)
    }

    @Test
    fun `scheduled downgrade succeeds while the current higher tier remains active`() = runBlocking {
        val ledger = FakeLedger(token, product).apply {
            additionalProducts += product.copy(
                tierCode = "TIER3",
                monthlyQuestionLimit = 1_000,
                productId = "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                sortOrder = 30,
            )
            projectedEntitlement = projectedEntitlement?.copy(
                tierCode = "TIER3",
                productId = "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                pendingProductId = product.productId,
            )
        }
        val service = service(ledger, membershipTierCode = "TIER3", monthlyLimit = 1_000)

        val result = service.syncAppleTransaction(principal(), syncCommand())

        assertEquals(InvoiceStatus.COMPLETED, result.status)
        assertEquals(BillingInvoicePhase.FULFILLED, result.phase)
        assertEquals("TIER3", ledger.projectedEntitlement?.tierCode)
        assertEquals(product.productId, ledger.projectedEntitlement?.pendingProductId)
    }

    @Test
    fun `downgrade without a matching pending product remains an application failure`() {
        val ledger = FakeLedger(token, product).apply {
            additionalProducts += product.copy(
                tierCode = "TIER3",
                monthlyQuestionLimit = 1_000,
                productId = "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                sortOrder = 30,
            )
            projectedEntitlement = projectedEntitlement?.copy(
                tierCode = "TIER3",
                productId = "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                pendingProductId = null,
            )
        }
        val service = service(ledger, membershipTierCode = "TIER3", monthlyLimit = 1_000)

        val error = assertThrows(ApiRuntimeException::class.java) {
            runBlocking { service.syncAppleTransaction(principal(), syncCommand()) }
        }

        assertEquals(ApiErrorCode.BILLING_APPLICATION_FAILED, error.errorCode)
    }

    @Test
    fun `scheduled downgrade with a stale current quota remains an application failure`() {
        val ledger = FakeLedger(token, product).apply {
            additionalProducts += product.copy(
                tierCode = "TIER3",
                monthlyQuestionLimit = 1_000,
                productId = "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                sortOrder = 30,
            )
            projectedEntitlement = projectedEntitlement?.copy(
                tierCode = "TIER3",
                productId = "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                pendingProductId = product.productId,
            )
        }
        val service = service(ledger, membershipTierCode = "TIER3", monthlyLimit = 300)

        val error = assertThrows(ApiRuntimeException::class.java) {
            runBlocking { service.syncAppleTransaction(principal(), syncCommand()) }
        }

        assertEquals(ApiErrorCode.BILLING_APPLICATION_FAILED, error.errorCode)
    }

    @Test
    fun `RevenueCat transaction confirmation applies the prepared invoice`() = runBlocking {
        val ledger = FakeLedger(token, product)
        val service = service(ledger)

        val result = service.confirmRevenueCatTransaction(
            principal(),
            invoiceNumber,
            ConfirmRevenueCatTransactionCommand(transaction.transactionId),
        )

        assertEquals(InvoiceStatus.COMPLETED, result.status)
        assertEquals(invoiceNumber, ledger.recordedPayments.single().invoiceNumber)
        assertEquals(transaction.transactionId, ledger.recordedPayments.single().transaction.transactionId)
        assertEquals(BillingEventSource.CLIENT, ledger.recordedPayments.single().source)
        assertTrue(ledger.recordedPayments.single().authoritativeOwnershipTransfer)
    }

    @Test
    fun `RevenueCat confirmation resolves the invoice purchase when SDK omits transaction ID`() = runBlocking {
        val ledger = FakeLedger(token, product)
        val verifier = RecordingRevenueCatVerifier(transaction)
        val service = service(ledger, revenueCatVerifier = verifier)

        val result = service.confirmRevenueCatTransaction(
            principal(),
            invoiceNumber,
            ConfirmRevenueCatTransactionCommand(null),
        )

        assertEquals(InvoiceStatus.COMPLETED, result.status)
        assertEquals(listOf(token to product.productId), verifier.latestRequests)
        assertTrue(verifier.transactionRequests.isEmpty())
        assertEquals(invoiceNumber, ledger.recordedPayments.single().invoiceNumber)
        assertTrue(ledger.recordedPayments.single().authoritativeOwnershipTransfer)
    }

    @Test
    fun `permanent RevenueCat verification failure fails the prepared invoice`() {
        val ledger = FakeLedger(token, product)
        val verifier = object : RevenueCatTransactionVerificationPort {
            override suspend fun verify(transactionId: String): VerifiedAppleTransaction = throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.BILLING_TRANSACTION_INVALID,
                "RevenueCat transaction ownership verification failed.",
            )

            override suspend fun verifyLatest(appAccountToken: UUID, productId: String): VerifiedAppleTransaction =
                error("not used")
        }
        val service = service(ledger, revenueCatVerifier = verifier)

        val error = assertThrows(ApiRuntimeException::class.java) {
            runBlocking {
                service.confirmRevenueCatTransaction(
                    principal(),
                    invoiceNumber,
                    ConfirmRevenueCatTransactionCommand(transaction.transactionId),
                )
            }
        }

        assertEquals(ApiErrorCode.BILLING_TRANSACTION_INVALID, error.errorCode)
        assertEquals(listOf(invoiceNumber), ledger.failedValidationInvoices)
        assertTrue(ledger.recordedPayments.isEmpty())
    }

    @Test
    fun `retryable RevenueCat verification delay keeps the prepared invoice waiting`() {
        val ledger = FakeLedger(token, product)
        val verifier = object : RevenueCatTransactionVerificationPort {
            override suspend fun verify(transactionId: String): VerifiedAppleTransaction = throw ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.BILLING_APPLICATION_FAILED,
                "RevenueCat has not indexed this transaction yet.",
            )

            override suspend fun verifyLatest(appAccountToken: UUID, productId: String): VerifiedAppleTransaction =
                error("not used")
        }
        val service = service(ledger, revenueCatVerifier = verifier)

        val error = assertThrows(ApiRuntimeException::class.java) {
            runBlocking {
                service.confirmRevenueCatTransaction(
                    principal(),
                    invoiceNumber,
                    ConfirmRevenueCatTransactionCommand(transaction.transactionId),
                )
            }
        }

        assertEquals(ApiErrorCode.BILLING_APPLICATION_FAILED, error.errorCode)
        assertTrue(ledger.failedValidationInvoices.isEmpty())
        assertTrue(ledger.recordedPayments.isEmpty())
    }

    @Test
    fun `malformed RevenueCat transaction identifier remains correctable`() {
        val ledger = FakeLedger(token, product)
        val verifier = RecordingRevenueCatVerifier(transaction)
        val service = service(ledger, revenueCatVerifier = verifier)

        val error = assertThrows(ApiRuntimeException::class.java) {
            runBlocking {
                service.confirmRevenueCatTransaction(
                    principal(),
                    invoiceNumber,
                    ConfirmRevenueCatTransactionCommand("invalid transaction id"),
                )
            }
        }

        assertEquals(ApiErrorCode.VALIDATION_ERROR, error.errorCode)
        assertTrue(ledger.failedValidationInvoices.isEmpty())
        assertTrue(verifier.transactionRequests.isEmpty())
        assertTrue(ledger.recordedPayments.isEmpty())
    }

    @Test
    fun `malformed Apple JWS fails a correlated prepared invoice`() {
        val ledger = FakeLedger(token, product)
        val service = service(ledger)

        val error = assertThrows(ApiRuntimeException::class.java) {
            runBlocking {
                service.syncAppleTransaction(
                    principal(),
                    SyncAppleTransactionCommand("not-a-jws", BillingEnvironment.SANDBOX, invoiceNumber),
                )
            }
        }

        assertEquals(ApiErrorCode.BILLING_TRANSACTION_INVALID, error.errorCode)
        assertEquals(listOf(invoiceNumber), ledger.failedValidationInvoices)
        assertTrue(ledger.recordedPayments.isEmpty())
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
        membershipTierCode: String = "TIER2",
        monthlyLimit: Int = 300,
        revenueCatVerifier: RevenueCatTransactionVerificationPort = RecordingRevenueCatVerifier(transaction),
    ) = object {
        val membership = object : QuestionMembershipPort {
            override suspend fun activePlanForUser(userId: Long) = QuestionMembershipPlan(membershipTierCode, monthlyLimit)
            override suspend fun quotaStatusForUser(userId: Long, at: Instant) = QuestionQuotaStatus(
                tierCode = membershipTierCode,
                usedCount = 0,
                monthlyQuestionLimit = monthlyLimit,
                baseLimit = monthlyLimit,
                periodStartedAt = now.minusSeconds(60),
                resetAt = now.plusSeconds(2_592_000),
            )
            override suspend fun tryConsumeMonthlySystemQuestion(
                userId: Long,
                periodStartedAt: Instant,
                limit: Int,
                now: Instant,
            ) = true
            override suspend fun refundMonthlySystemQuestion(userId: Long, periodStartedAt: Instant, now: Instant) = Unit
        }
        val appleVerifier = object : AppleBillingVerificationPort {
            override suspend fun verifyTransaction(
                signedTransaction: String,
                environment: BillingEnvironment,
            ): VerifiedAppleTransaction = transaction

            override suspend fun verifyNotification(signedPayload: String): VerifiedAppleNotification =
                notification ?: error("not used")
        }
        val service = BillingService(
            verifier = appleVerifier,
            revenueCatTransactionVerifier = revenueCatVerifier,
            verifiedPayments = VerifiedBillingPaymentService(ledger, membership),
            ledger = ledger,
            memberships = membership,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
    }.service

    private class RecordingRevenueCatVerifier(
        private val transaction: VerifiedAppleTransaction,
    ) : RevenueCatTransactionVerificationPort {
        val transactionRequests = mutableListOf<String>()
        val latestRequests = mutableListOf<Pair<UUID, String>>()

        override suspend fun verify(transactionId: String): VerifiedAppleTransaction {
            transactionRequests += transactionId
            return transaction
        }

        override suspend fun verifyLatest(
            appAccountToken: UUID,
            productId: String,
        ): VerifiedAppleTransaction {
            latestRequests += appAccountToken to productId
            return transaction
        }
    }

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
        private val productEnabled: Boolean = true,
    ) : BillingLedgerPort {
        var projectedEntitlement: BillingEntitlementProjection? = BillingEntitlementProjection(
            tierCode = "TIER2",
            source = EntitlementSource.APP_STORE,
            accessStatus = SubscriptionAccessStatus.ACTIVE,
            renewalStatus = SubscriptionRenewalStatus.WILL_RENEW,
            productId = "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
            startedAt = Instant.parse("2026-08-02T23:59:50Z"),
            expiresAt = Instant.parse("2026-09-02T00:00:00Z"),
            willRenew = true,
            pendingProductId = null,
            synchronizedAt = Instant.parse("2026-08-03T00:00:00Z"),
        )
        val additionalProducts = mutableListOf<BillingTierProduct>()
        override suspend fun entitlementForUser(userId: Long): BillingEntitlementProjection? = projectedEntitlement
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
        val failedValidationInvoices = mutableListOf<UUID>()
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
        override suspend fun enabledTierProducts(): List<BillingTierProduct> =
            if (productEnabled) listOfNotNull(product) else emptyList()
        override suspend fun enabledTierProduct(productId: String): BillingTierProduct? =
            product?.takeIf { productEnabled && it.productId == productId }
        override suspend fun tierProduct(productId: String): BillingTierProduct? =
            product?.takeIf { it.productId == productId } ?: additionalProducts.firstOrNull { it.productId == productId }
        override suspend fun createPendingInvoice(
            userId: Long,
            appAccountToken: UUID,
            tierProduct: BillingTierProduct,
            idempotencyKey: String,
            now: Instant,
        ): BillingInvoiceSummary {
            pendingCheckoutKeys += idempotencyKey
            return invoice(InvoiceStatus.WAITING, hasPayment = false)
        }
        override suspend fun abandonPendingInvoice(
            userId: Long,
            invoiceNumber: UUID,
            now: Instant,
        ): BillingInvoiceSummary = invoice(InvoiceStatus.FAILED)
        override suspend fun failPendingInvoiceValidation(
            userId: Long,
            invoiceNumber: UUID,
            source: BillingEventSource,
            reason: String,
            now: Instant,
        ): BillingInvoiceSummary {
            failedValidationInvoices += invoiceNumber
            return invoice(InvoiceStatus.FAILED, hasPayment = false)
        }
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
            return invoice(InvoiceStatus.COMPLETED, applied = true)
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
        override suspend fun invoiceByNumber(userId: Long, invoiceNumber: UUID): BillingInvoiceSummary =
            invoice(InvoiceStatus.WAITING, hasPayment = false)
        override suspend fun latestPendingInvoice(userId: Long): BillingInvoiceSummary =
            invoice(InvoiceStatus.WAITING, hasPayment = false)
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

        private fun invoice(
            status: InvoiceStatus,
            applied: Boolean = false,
            hasPayment: Boolean = true,
        ) = BillingInvoiceSummary(
            id = 99,
            invoiceNumber = UUID.fromString("2306d81d-1323-48c4-bb2b-a40cc48f70da"),
            type = InvoiceType.NORMAL,
            originalInvoiceId = null,
            tierCode = "TIER2",
            productId = "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
            status = status,
            version = 2,
            paymentId = if (hasPayment) 88 else null,
            transactionId = if (hasPayment) "200000000000001" else null,
            originalTransactionId = if (hasPayment) "200000000000000" else null,
            paymentStatus = if (!hasPayment) null else if (applied) PaymentStatus.SETTLED else PaymentStatus.VERIFIED,
            priceMilliunits = if (hasPayment) 4_900_000 else null,
            currency = if (hasPayment) "KRW" else null,
            purchaseAt = if (hasPayment) Instant.parse("2026-08-02T23:59:50Z") else null,
            expiresAt = if (hasPayment) Instant.parse("2026-09-02T00:00:00Z") else null,
            createdAt = Instant.parse("2026-08-03T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-03T00:00:00Z"),
            fulfilledAt = if (applied) Instant.parse("2026-08-03T00:00:00Z") else null,
        )

        private fun action(type: BillingActionType) = BillingAction(
            UUID.randomUUID(), type, BillingActionStatus.AWAITING_APPLE, 99, 88,
            "200000000000001", "200000000000000", null, Instant.now(), null,
            if (type == BillingActionType.REFUND) BillingClientAction.BEGIN_APPLE_REFUND_REQUEST
            else BillingClientAction.OPEN_APPLE_SUBSCRIPTION_MANAGEMENT,
        )
    }
}
