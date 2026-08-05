package com.buddystudy.backend.billing.application.service

import com.buddystudy.backend.billing.application.model.RecordVerifiedPaymentCommand
import com.buddystudy.backend.billing.application.model.RevenueCatWebhookRequest
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.model.VerifiedRevenueCatEvent
import com.buddystudy.backend.billing.application.port.inbound.RevenueCatBillingNotificationUseCase
import com.buddystudy.backend.billing.application.port.inbound.RevenueCatEventProjectionUseCase
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatWebhookVerificationPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.billing.domain.BillingEventSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class RevenueCatBillingService(
    private val verifier: RevenueCatWebhookVerificationPort,
    private val ledger: BillingLedgerPort,
    private val clock: Clock = Clock.systemUTC(),
    @param:Value("\${buddystudy.billing.revenue-cat.allow-test-store:false}")
    private val allowTestStore: Boolean = false,
) : RevenueCatBillingNotificationUseCase, RevenueCatEventProjectionUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun receive(request: RevenueCatWebhookRequest) {
        val event = verifier.verify(request)
        if (event.eventType in PURCHASE_EVENT_TYPES) event.validateAcceptedStore()
        ledger.recordRevenueCatEvent(event, clock.instant())
    }

    override suspend fun projectDueEvents(): Int {
        val events = ledger.claimDueRevenueCatEvents(clock.instant(), 100)
        events.forEach { event ->
            val now = clock.instant()
            try {
                if (event.eventType in PURCHASE_EVENT_TYPES) {
                    val transaction = event.toVerifiedAppleTransaction()
                    val userId = ledger.userIdForAppAccountToken(transaction.appAccountToken)
                        ?: invalidEvent("RevenueCat App User ID is not mapped to a BuddyStudy account.")
                    val product = ledger.enabledTierProduct(transaction.productId)
                        ?: invalidEvent("RevenueCat product is not mapped to a membership tier.")
                    if (product.productType != transaction.productType) {
                        invalidEvent("RevenueCat product type does not match the membership catalog.")
                    }
                    val invoice = ledger.recordVerifiedPayment(
                        RecordVerifiedPaymentCommand(
                            userId = userId,
                            tierProduct = product,
                            transaction = transaction,
                            invoiceNumber = null,
                            source = BillingEventSource.REVENUECAT_WEBHOOK,
                            eventId = "apple-transaction:${transaction.transactionId}",
                            occurredAt = now,
                        ),
                    )
                    ledger.fulfill(invoice.id, now)
                }
                ledger.applyRevenueCatEvent(event, now)
            } catch (error: Exception) {
                ledger.markRevenueCatEventFailed(
                    event.eventId,
                    (error.message ?: error.javaClass.name).take(4000),
                    clock.instant(),
                )
                logger.error(
                    "revenuecat_subscription_event_projection_failed eventId={} eventType={} errorType={} message={}",
                    event.eventId,
                    event.eventType,
                    error.javaClass.name,
                    error.message,
                    error,
                )
            }
        }
        return events.size
    }

    private fun VerifiedRevenueCatEvent.toVerifiedAppleTransaction(): VerifiedAppleTransaction {
        validateAcceptedStore()
        val token = sequenceOf(appUserId, originalAppUserId).plus(aliases.asSequence())
            .filterNotNull()
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .firstOrNull()
            ?: invalidEvent("RevenueCat App User ID must be the BuddyStudy appAccountToken UUID.")
        val resolvedProductId = productId?.takeIf { PRODUCT_ID.matches(it) }
            ?: invalidEvent("RevenueCat product ID is missing or invalid.")
        val resolvedTransactionId = transactionId?.takeIf { PROVIDER_ID.matches(it) }
            ?: invalidEvent("RevenueCat transaction ID is missing or invalid.")
        val resolvedOriginalTransactionId = originalTransactionId?.takeIf { PROVIDER_ID.matches(it) }
            ?: invalidEvent("RevenueCat original transaction ID is missing or invalid.")
        val resolvedEnvironment = environment
            ?: invalidEvent("RevenueCat environment is missing or invalid.")
        val resolvedPurchasedAt = purchasedAt
            ?: invalidEvent("RevenueCat purchase timestamp is missing.")

        return VerifiedAppleTransaction(
            transactionId = resolvedTransactionId,
            originalTransactionId = resolvedOriginalTransactionId,
            appTransactionId = null,
            webOrderLineItemId = null,
            appAccountToken = token,
            productId = resolvedProductId,
            productType = com.buddystudy.billing.domain.BillingProductType.AUTO_RENEWABLE_SUBSCRIPTION,
            environment = resolvedEnvironment,
            quantity = 1,
            priceMilliunits = priceMilliunits?.takeIf { it >= 0 },
            currency = currency,
            purchaseAt = resolvedPurchasedAt,
            originalPurchaseAt = null,
            expiresAt = expiresAt,
            revocationAt = null,
            revocationReason = null,
            signedAt = eventAt.coerceAtLeast(resolvedPurchasedAt),
            signedPayloadSha256 = signedPayloadSha256,
        )
    }

    private fun VerifiedRevenueCatEvent.validateAcceptedStore() {
        val acceptedStore = store == "APP_STORE" || (store == "TEST_STORE" && allowTestStore)
        if (!acceptedStore) invalidEvent("RevenueCat store is not accepted in this environment.")
    }

    private fun invalidEvent(message: String): Nothing = throw ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ApiErrorCode.BILLING_TRANSACTION_INVALID,
        message,
    )

    private fun Instant.coerceAtLeast(other: Instant): Instant = if (isBefore(other)) other else this

    private companion object {
        val PURCHASE_EVENT_TYPES = setOf("INITIAL_PURCHASE", "RENEWAL", "NON_RENEWING_PURCHASE")
        val PROVIDER_ID = Regex("^[A-Za-z0-9._:-]{1,191}$")
        val PRODUCT_ID = Regex("^[A-Za-z0-9._-]{1,191}$")
    }
}
