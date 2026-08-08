package com.buddystudy.backend.billing

import com.buddystudy.backend.billing.application.model.BillingInvoiceSummary
import com.buddystudy.backend.billing.application.model.BillingTierProduct
import com.buddystudy.backend.billing.application.model.ApplyVerifiedBillingPaymentCommand
import com.buddystudy.backend.billing.application.model.RevenueCatWebhookRequest
import com.buddystudy.backend.billing.application.model.VerifiedRevenueCatEvent
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatTransactionVerificationPort
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatWebhookVerificationPort
import com.buddystudy.backend.billing.application.port.inbound.VerifiedBillingPaymentUseCase
import com.buddystudy.backend.billing.application.service.RevenueCatBillingService
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.BillingProductType
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.InvoiceType
import com.buddystudy.billing.domain.PaymentStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RevenueCatBillingServiceTest {
    private val now = Instant.parse("2026-08-04T00:00:00Z")
    private val token = UUID.fromString("3f0c5f50-6521-4ba0-a990-73500e915f57")
    private val request = RevenueCatWebhookRequest("{}".toByteArray(), "t=1,v1=${"a".repeat(64)}")
    private val event = VerifiedRevenueCatEvent(
        eventId = "rc-event-1",
        eventType = "INITIAL_PURCHASE",
        appUserId = token.toString(),
        originalAppUserId = token.toString(),
        aliases = emptyList(),
        store = "APP_STORE",
        productId = "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
        transactionId = "200000000000001",
        originalTransactionId = "200000000000000",
        environment = BillingEnvironment.SANDBOX,
        priceMilliunits = 7_900_000,
        currency = "KRW",
        purchasedAt = now.minusSeconds(10),
        expiresAt = now.plusSeconds(2_592_000),
        eventAt = now,
        cancelReason = null,
        expirationReason = null,
        signedPayloadSha256 = "b".repeat(64),
    )
    private val product = BillingTierProduct(
        tierCode = "TIER2",
        description = "Tier 2",
        monthlyQuestionLimit = 300,
        productId = event.productId!!,
        productType = BillingProductType.AUTO_RENEWABLE_SUBSCRIPTION,
        billingPeriod = "P1M",
        sortOrder = 20,
    )

    @Test
    fun `initial purchase webhook recovers payment with the Apple transaction idempotency key`() = runBlocking<Unit> {
        val verifier = Mockito.mock(RevenueCatWebhookVerificationPort::class.java)
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val payments = Mockito.mock(VerifiedBillingPaymentUseCase::class.java)
        Mockito.`when`(verifier.verify(request)).thenReturn(event)
        Mockito.`when`(ledger.recordRevenueCatEvent(event, now)).thenReturn(true)
        Mockito.`when`(ledger.userIdForAppAccountToken(token)).thenReturn(733)
        Mockito.`when`(ledger.tierProduct(product.productId)).thenReturn(product)

        val expectedCommand = paymentCommand(event)
        Mockito.`when`(payments.apply(expectedCommand)).thenReturn(invoice(InvoiceStatus.COMPLETED))
        Mockito.`when`(ledger.applyRevenueCatEvent(event, now)).thenReturn(true)

        val service = RevenueCatBillingService(verifier, ledger, payments, transactionVerifier(), Clock.fixed(now, ZoneOffset.UTC))
        service.receive(request)
        Mockito.verify(payments, Mockito.never()).apply(expectedCommand)

        Mockito.`when`(ledger.claimDueRevenueCatEvents(now, 100)).thenReturn(listOf(event))
        service.projectDueEvents()

        Mockito.verify(payments).apply(expectedCommand)
    }

    @Test
    fun `duplicate RevenueCat event stops before payment fulfillment`() = runBlocking<Unit> {
        val verifier = Mockito.mock(RevenueCatWebhookVerificationPort::class.java)
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val payments = Mockito.mock(VerifiedBillingPaymentUseCase::class.java)
        Mockito.`when`(verifier.verify(request)).thenReturn(event)
        Mockito.`when`(ledger.recordRevenueCatEvent(event, now)).thenReturn(false)

        val service = RevenueCatBillingService(verifier, ledger, payments, transactionVerifier(), Clock.fixed(now, ZoneOffset.UTC))
        service.receive(request)

        Mockito.verify(ledger, Mockito.never()).userIdForAppAccountToken(token)
    }

    @Test
    fun `purchase resolves stable account token from RevenueCat original app user id`() = runBlocking<Unit> {
        val aliasedEvent = event.copy(
            eventId = "rc-event-aliased",
            appUserId = "\$RCAnonymousID:temporary-device-user",
            originalAppUserId = token.toString(),
        )
        val verifier = Mockito.mock(RevenueCatWebhookVerificationPort::class.java)
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val payments = Mockito.mock(VerifiedBillingPaymentUseCase::class.java)
        Mockito.`when`(verifier.verify(request)).thenReturn(aliasedEvent)
        Mockito.`when`(ledger.recordRevenueCatEvent(aliasedEvent, now)).thenReturn(true)
        Mockito.`when`(ledger.userIdForAppAccountToken(token)).thenReturn(733)
        Mockito.`when`(ledger.tierProduct(product.productId)).thenReturn(product)
        Mockito.`when`(payments.apply(paymentCommand(aliasedEvent))).thenReturn(invoice(InvoiceStatus.COMPLETED))
        Mockito.`when`(ledger.applyRevenueCatEvent(aliasedEvent, now)).thenReturn(true)

        val service = RevenueCatBillingService(verifier, ledger, payments, transactionVerifier(), Clock.fixed(now, ZoneOffset.UTC))
        service.receive(request)
        Mockito.`when`(ledger.claimDueRevenueCatEvents(now, 100)).thenReturn(listOf(aliasedEvent))
        service.projectDueEvents()

        Mockito.verify(ledger).userIdForAppAccountToken(token)
    }

    @Test
    fun `test store purchase is accepted only when explicitly enabled`() = runBlocking<Unit> {
        val testStoreEvent = event.copy(store = "TEST_STORE")
        val verifier = Mockito.mock(RevenueCatWebhookVerificationPort::class.java)
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val payments = Mockito.mock(VerifiedBillingPaymentUseCase::class.java)
        Mockito.`when`(verifier.verify(request)).thenReturn(testStoreEvent)
        Mockito.`when`(ledger.recordRevenueCatEvent(testStoreEvent, now)).thenReturn(true)
        Mockito.`when`(ledger.userIdForAppAccountToken(token)).thenReturn(733)
        Mockito.`when`(ledger.tierProduct(product.productId)).thenReturn(product)
        Mockito.`when`(payments.apply(paymentCommand(testStoreEvent))).thenReturn(invoice(InvoiceStatus.COMPLETED))
        Mockito.`when`(ledger.applyRevenueCatEvent(testStoreEvent, now)).thenReturn(true)

        val service = RevenueCatBillingService(
            verifier,
            ledger,
            payments,
            transactionVerifier(),
            Clock.fixed(now, ZoneOffset.UTC),
            allowTestStore = true,
        )
        service.receive(request)
        Mockito.`when`(ledger.claimDueRevenueCatEvents(now, 100)).thenReturn(listOf(testStoreEvent))
        service.projectDueEvents()

        Mockito.verify(payments).apply(paymentCommand(testStoreEvent))
    }

    @Test
    fun `test store purchase is rejected before persistence when disabled`() = runBlocking<Unit> {
        val testStoreEvent = event.copy(store = "TEST_STORE")
        val verifier = Mockito.mock(RevenueCatWebhookVerificationPort::class.java)
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val payments = Mockito.mock(VerifiedBillingPaymentUseCase::class.java)
        Mockito.`when`(verifier.verify(request)).thenReturn(testStoreEvent)

        val service = RevenueCatBillingService(
            verifier,
            ledger,
            payments,
            transactionVerifier(),
            Clock.fixed(now, ZoneOffset.UTC),
            allowTestStore = false,
        )
        val failure = runCatching { service.receive(request) }.exceptionOrNull()

        assertInstanceOf(com.buddystudy.backend.common.application.error.ApiException::class.java, failure)
        Mockito.verify(ledger, Mockito.never()).recordRevenueCatEvent(testStoreEvent, now)
    }

    @Test
    fun `invalid projected event is failed durably without blocking the next event`() = runBlocking<Unit> {
        val invalidEvent = event.copy(
            eventId = "rc-event-invalid-user",
            appUserId = "not-a-buddystudy-account-token",
            originalAppUserId = null,
        )
        val nextEvent = event.copy(
            eventId = "rc-event-after-invalid",
            transactionId = "200000000000002",
        )
        val verifier = Mockito.mock(RevenueCatWebhookVerificationPort::class.java)
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val payments = Mockito.mock(VerifiedBillingPaymentUseCase::class.java)
        Mockito.`when`(ledger.claimDueRevenueCatEvents(now, 100)).thenReturn(listOf(invalidEvent, nextEvent))
        Mockito.`when`(ledger.userIdForAppAccountToken(token)).thenReturn(733)
        Mockito.`when`(ledger.tierProduct(product.productId)).thenReturn(product)
        Mockito.`when`(payments.apply(paymentCommand(nextEvent))).thenReturn(invoice(InvoiceStatus.COMPLETED))
        Mockito.`when`(ledger.applyRevenueCatEvent(nextEvent, now)).thenReturn(true)

        val service = RevenueCatBillingService(verifier, ledger, payments, transactionVerifier(), Clock.fixed(now, ZoneOffset.UTC))
        service.projectDueEvents()

        Mockito.verify(ledger).markRevenueCatEventFailed(
            invalidEvent.eventId,
            "RevenueCat App User ID must be the BuddyStudy appAccountToken UUID.",
            now,
        )
        Mockito.verify(payments).apply(paymentCommand(nextEvent))
        Mockito.verify(ledger).applyRevenueCatEvent(nextEvent, now)
    }

    @Test
    fun `transfer webhook recovers the latest prepared invoice`() = runBlocking<Unit> {
        val transfer = event.copy(
            eventId = "rc-transfer-1",
            eventType = "TRANSFER",
            productId = null,
            transactionId = null,
            originalTransactionId = null,
            purchasedAt = null,
            expiresAt = null,
        )
        val verifier = Mockito.mock(RevenueCatWebhookVerificationPort::class.java)
        val transactionVerifier = Mockito.mock(RevenueCatTransactionVerificationPort::class.java)
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val payments = Mockito.mock(VerifiedBillingPaymentUseCase::class.java)
        val pendingInvoice = invoice().copy(
            paymentId = null,
            transactionId = null,
            originalTransactionId = null,
            paymentStatus = null,
        )
        val transaction = paymentCommand(event).transaction
        Mockito.`when`(ledger.claimDueRevenueCatEvents(now, 100)).thenReturn(listOf(transfer))
        Mockito.`when`(ledger.userIdForAppAccountToken(token)).thenReturn(733)
        Mockito.`when`(ledger.latestPendingInvoice(733)).thenReturn(pendingInvoice)
        Mockito.`when`(transactionVerifier.verifyLatest(token, pendingInvoice.productId)).thenReturn(transaction)
        Mockito.`when`(ledger.tierProduct(product.productId)).thenReturn(product)
        Mockito.`when`(ledger.applyRevenueCatEvent(transfer, now)).thenReturn(true)

        val service = RevenueCatBillingService(
            verifier,
            ledger,
            payments,
            transactionVerifier,
            Clock.fixed(now, ZoneOffset.UTC),
        )
        service.projectDueEvents()

        Mockito.verify(payments).apply(
            ApplyVerifiedBillingPaymentCommand(
                userId = 733,
                tierProduct = product,
                transaction = transaction,
                invoiceNumber = pendingInvoice.invoiceNumber,
                source = BillingEventSource.REVENUECAT_WEBHOOK,
                occurredAt = now,
            ),
        )
        Mockito.verify(ledger).applyRevenueCatEvent(transfer, now)
    }

    private fun transactionVerifier(): RevenueCatTransactionVerificationPort =
        Mockito.mock(RevenueCatTransactionVerificationPort::class.java)

    private fun paymentCommand(source: VerifiedRevenueCatEvent) = ApplyVerifiedBillingPaymentCommand(
        userId = 733,
        tierProduct = product,
        transaction = VerifiedAppleTransaction(
            transactionId = source.transactionId!!,
            originalTransactionId = source.originalTransactionId!!,
            appTransactionId = null,
            webOrderLineItemId = null,
            appAccountToken = token,
            productId = product.productId,
            productType = BillingProductType.AUTO_RENEWABLE_SUBSCRIPTION,
            environment = BillingEnvironment.SANDBOX,
            quantity = 1,
            priceMilliunits = source.priceMilliunits,
            currency = source.currency,
            purchaseAt = source.purchasedAt!!,
            originalPurchaseAt = null,
            expiresAt = source.expiresAt,
            revocationAt = null,
            revocationReason = null,
            signedAt = source.eventAt,
            signedPayloadSha256 = source.signedPayloadSha256,
        ),
        invoiceNumber = null,
        source = BillingEventSource.REVENUECAT_WEBHOOK,
        occurredAt = now,
    )

    private fun invoice(status: InvoiceStatus = InvoiceStatus.WAITING) = BillingInvoiceSummary(
        id = 99,
        invoiceNumber = UUID.fromString("2306d81d-1323-48c4-bb2b-a40cc48f70da"),
        type = InvoiceType.NORMAL,
        originalInvoiceId = null,
        tierCode = product.tierCode,
        productId = product.productId,
        status = status,
        version = 2,
        paymentId = 88,
        transactionId = event.transactionId,
        originalTransactionId = event.originalTransactionId,
        paymentStatus = PaymentStatus.VERIFIED,
        priceMilliunits = event.priceMilliunits,
        currency = event.currency,
        purchaseAt = event.purchasedAt,
        expiresAt = event.expiresAt,
        createdAt = now,
        updatedAt = now,
    )
}
