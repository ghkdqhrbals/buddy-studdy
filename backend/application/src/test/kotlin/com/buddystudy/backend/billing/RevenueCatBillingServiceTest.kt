package com.buddystudy.backend.billing

import com.buddystudy.backend.billing.application.model.BillingInvoiceSummary
import com.buddystudy.backend.billing.application.model.BillingTierProduct
import com.buddystudy.backend.billing.application.model.RecordVerifiedPaymentCommand
import com.buddystudy.backend.billing.application.model.RevenueCatWebhookRequest
import com.buddystudy.backend.billing.application.model.VerifiedRevenueCatEvent
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatWebhookVerificationPort
import com.buddystudy.backend.billing.application.service.RevenueCatBillingService
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.BillingProductType
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.InvoiceType
import com.buddystudy.billing.domain.PaymentStatus
import kotlinx.coroutines.runBlocking
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
        Mockito.`when`(verifier.verify(request)).thenReturn(event)
        Mockito.`when`(ledger.recordRevenueCatEvent(event, now)).thenReturn(true)
        Mockito.`when`(ledger.userIdForAppAccountToken(token)).thenReturn(733)
        Mockito.`when`(ledger.enabledTierProduct(product.productId)).thenReturn(product)

        val expectedCommand = RecordVerifiedPaymentCommand(
            userId = 733,
            tierProduct = product,
            transaction = VerifiedAppleTransaction(
                transactionId = event.transactionId!!,
                originalTransactionId = event.originalTransactionId!!,
                appTransactionId = null,
                webOrderLineItemId = null,
                appAccountToken = token,
                productId = product.productId,
                productType = BillingProductType.AUTO_RENEWABLE_SUBSCRIPTION,
                environment = BillingEnvironment.SANDBOX,
                quantity = 1,
                priceMilliunits = event.priceMilliunits,
                currency = event.currency,
                purchaseAt = event.purchasedAt!!,
                originalPurchaseAt = null,
                expiresAt = event.expiresAt,
                revocationAt = null,
                revocationReason = null,
                signedAt = event.eventAt,
                signedPayloadSha256 = event.signedPayloadSha256,
            ),
            invoiceNumber = null,
            source = BillingEventSource.REVENUECAT_WEBHOOK,
            eventId = "apple-transaction:${event.transactionId}",
            occurredAt = now,
        )
        Mockito.`when`(ledger.recordVerifiedPayment(expectedCommand)).thenReturn(invoice())
        Mockito.`when`(ledger.fulfill(99, now)).thenReturn(invoice(InvoiceStatus.COMPLETED))
        Mockito.`when`(ledger.applyRevenueCatEvent(event, now)).thenReturn(true)

        RevenueCatBillingService(verifier, ledger, Clock.fixed(now, ZoneOffset.UTC)).receive(request)

        Mockito.verify(ledger).recordVerifiedPayment(expectedCommand)
        Mockito.verify(ledger).fulfill(99, now)
    }

    @Test
    fun `duplicate RevenueCat event stops before payment fulfillment`() = runBlocking<Unit> {
        val verifier = Mockito.mock(RevenueCatWebhookVerificationPort::class.java)
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        Mockito.`when`(verifier.verify(request)).thenReturn(event)
        Mockito.`when`(ledger.recordRevenueCatEvent(event, now)).thenReturn(false)

        RevenueCatBillingService(verifier, ledger, Clock.fixed(now, ZoneOffset.UTC)).receive(request)

        Mockito.verify(ledger, Mockito.never()).userIdForAppAccountToken(token)
    }

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
