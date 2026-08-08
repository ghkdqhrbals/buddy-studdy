package com.buddystudy.backend.billing.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.billing.application.model.ApplyAppleNotificationCommand
import com.buddystudy.backend.billing.application.model.BillingAction
import com.buddystudy.backend.billing.application.model.BillingCatalog
import com.buddystudy.backend.billing.application.model.BillingEntitlementProjection
import com.buddystudy.backend.billing.application.model.BillingInvoiceDetail
import com.buddystudy.backend.billing.application.model.BillingInvoicePage
import com.buddystudy.backend.billing.application.model.BillingInvoicePhase
import com.buddystudy.backend.billing.application.model.BillingInvoiceSummary
import com.buddystudy.backend.billing.application.model.BillingRecoveryResult
import com.buddystudy.backend.billing.application.model.BillingStatusResponse
import com.buddystudy.backend.billing.application.model.BillingQuotaStatus
import com.buddystudy.backend.billing.application.model.BillingPlanTransition
import com.buddystudy.backend.billing.application.model.CreateBillingCheckoutCommand
import com.buddystudy.backend.billing.application.model.ConfirmRevenueCatTransactionCommand
import com.buddystudy.backend.billing.application.model.ApplyVerifiedBillingPaymentCommand
import com.buddystudy.backend.billing.application.model.RecordVerifiedPaymentCommand
import com.buddystudy.backend.billing.application.model.RequestBillingActionCommand
import com.buddystudy.backend.billing.application.model.SyncAppleTransactionCommand
import com.buddystudy.backend.billing.application.model.VerifiedAppleTransaction
import com.buddystudy.backend.billing.application.port.inbound.AppleBillingNotificationUseCase
import com.buddystudy.backend.billing.application.port.inbound.BillingUseCase
import com.buddystudy.backend.billing.application.port.inbound.BillingRecoveryUseCase
import com.buddystudy.backend.billing.application.port.inbound.VerifiedBillingPaymentUseCase
import com.buddystudy.backend.billing.application.port.outbound.AppleBillingVerificationPort
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatTransactionVerificationPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.EntitlementSource
import com.buddystudy.billing.domain.SubscriptionAccessStatus
import com.buddystudy.billing.domain.SubscriptionRenewalStatus
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class BillingService(
    private val verifier: AppleBillingVerificationPort,
    private val revenueCatTransactionVerifier: RevenueCatTransactionVerificationPort,
    private val verifiedPayments: VerifiedBillingPaymentUseCase,
    private val ledger: BillingLedgerPort,
    private val memberships: QuestionMembershipPort,
    private val clock: Clock = Clock.systemUTC(),
) : BillingUseCase, AppleBillingNotificationUseCase, BillingRecoveryUseCase {
    override suspend fun status(principal: Principal): BillingStatusResponse {
        requireRegistered(principal)
        val now = clock.instant()
        val entitlement = ledger.entitlementForUser(principal.userId)
            ?.takeUnless { it.isExpiredAt(now) }
        val quota = memberships.quotaStatusForUser(principal.userId, now)
            ?: throw billingError(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Question quota was not found.")
        val periodStartedAt = quota.periodStartedAt ?: now
        val resetAt = quota.resetAt ?: now
        val planTransition = entitlement?.let { current ->
            val changesAt = current.expiresAt
            if (changesAt == null || !current.accessStatus.grantsAccess()) {
                null
            } else if (current.pendingProductId != null) {
                ledger.tierProduct(current.pendingProductId)
                    ?.takeIf { it.tierCode != current.tierCode }
                    ?.let { next ->
                        BillingPlanTransition(
                            currentTierCode = current.tierCode,
                            currentProductId = current.productId,
                            currentPlanEndsAt = changesAt,
                            nextTierCode = next.tierCode,
                            nextProductId = next.productId,
                            nextPlanStartsAt = changesAt,
                        )
                    }
            } else if (
                !current.willRenew &&
                current.renewalStatus == SubscriptionRenewalStatus.CANCELED &&
                current.productId != null
            ) {
                BillingPlanTransition(
                    currentTierCode = current.tierCode,
                    currentProductId = current.productId,
                    currentPlanEndsAt = changesAt,
                    nextTierCode = "TIER1",
                    nextProductId = null,
                    nextPlanStartsAt = changesAt,
                )
            } else {
                null
            }
        }
        return BillingStatusResponse(
            tierCode = entitlement?.tierCode ?: quota.tierCode,
            source = entitlement?.source ?: EntitlementSource.FREE,
            accessStatus = entitlement?.accessStatus ?: SubscriptionAccessStatus.ACTIVE,
            renewalStatus = entitlement?.renewalStatus ?: SubscriptionRenewalStatus.NOT_APPLICABLE,
            productId = entitlement?.productId,
            startedAt = entitlement?.startedAt,
            expiresAt = entitlement?.expiresAt,
            willRenew = entitlement?.willRenew ?: false,
            pendingChange = entitlement?.pendingProductId,
            planTransition = planTransition,
            synchronizedAt = entitlement?.synchronizedAt ?: now,
            quota = BillingQuotaStatus(
                periodStartedAt = periodStartedAt,
                resetAt = resetAt,
                anchorType = quota.anchorType,
                baseLimit = quota.baseLimit,
                bonusLimit = quota.bonusLimit,
                usedCount = quota.usedCount,
                reservedCount = quota.reservedCount,
                remainingCount = (quota.monthlyQuestionLimit - quota.usedCount - quota.reservedCount).coerceAtLeast(0),
                policyVersion = quota.policyVersion,
            ),
        )
    }

    private fun SubscriptionAccessStatus.grantsAccess(): Boolean =
        this == SubscriptionAccessStatus.ACTIVE || this == SubscriptionAccessStatus.GRACE_PERIOD

    private fun BillingEntitlementProjection.isExpiredAt(now: Instant): Boolean =
        source == EntitlementSource.APP_STORE &&
            accessStatus == SubscriptionAccessStatus.ACTIVE &&
            expiresAt?.isAfter(now) == false
    override suspend fun catalog(principal: Principal): BillingCatalog {
        requireRegistered(principal)
        val now = clock.instant()
        return BillingCatalog(
            appAccountToken = ledger.findOrCreateAppAccountToken(principal.userId, now),
            products = ledger.enabledTierProducts(),
        )
    }

    override suspend fun createCheckout(
        principal: Principal,
        command: CreateBillingCheckoutCommand,
    ): BillingInvoiceSummary {
        requireRegistered(principal)
        val productId = command.productId.trim()
        if (!PRODUCT_ID.matches(productId)) invalidTransaction()
        validateIdempotencyKey(command.idempotencyKey)
        val product = ledger.enabledTierProduct(productId)
            ?: throw billingError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.BILLING_TRANSACTION_INVALID,
                "The App Store product is not enabled for a BuddyStudy tier.",
            )
        val now = clock.instant()
        val token = ledger.findOrCreateAppAccountToken(principal.userId, now)
        return ledger.createPendingInvoice(
            userId = principal.userId,
            appAccountToken = token,
            tierProduct = product,
            idempotencyKey = command.idempotencyKey.trim(),
            now = now,
        )
    }

    override suspend fun abandonCheckout(principal: Principal, invoiceNumber: UUID): BillingInvoiceSummary {
        requireRegistered(principal)
        return ledger.abandonPendingInvoice(principal.userId, invoiceNumber, clock.instant())
    }

    override suspend fun confirmRevenueCatTransaction(
        principal: Principal,
        invoiceNumber: UUID,
        command: ConfirmRevenueCatTransactionCommand,
    ): BillingInvoiceSummary {
        requireRegistered(principal)
        val now = clock.instant()
        val invoice = ledger.invoiceByNumber(principal.userId, invoiceNumber)
            ?: throw billingError(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice not found.")
        if (invoice.phase == BillingInvoicePhase.FULFILLED) {
            return invoice
        }
        if (invoice.phase == BillingInvoicePhase.FAILED) {
            throw billingError(
                HttpStatus.CONFLICT,
                ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                "The billing invoice is no longer available for confirmation.",
            )
        }
        val transactionId = command.transactionId?.trim()?.takeIf(String::isNotEmpty)
        val transaction = if (transactionId != null) {
            validateProviderId(transactionId, "transactionId")
            revenueCatTransactionVerifier.verify(transactionId)
        } else {
            val appAccountToken = ledger.findOrCreateAppAccountToken(principal.userId, now)
            revenueCatTransactionVerifier.verifyLatest(appAccountToken, invoice.productId)
        }
        validateTransaction(transaction, now)
        return applyVerifiedTransaction(principal, transaction, invoiceNumber, BillingEventSource.CLIENT, now)
    }

    override suspend fun syncAppleTransaction(
        principal: Principal,
        command: SyncAppleTransactionCommand,
    ): BillingInvoiceSummary {
        requireRegistered(principal)
        validateSignedPayload(command.signedTransaction)

        val transaction = verifier.verifyTransaction(command.signedTransaction, command.environment)
        val now = clock.instant()
        validateTransaction(transaction, now)

        return applyVerifiedTransaction(
            principal,
            transaction,
            command.invoiceNumber,
            BillingEventSource.CLIENT,
            now,
        )
    }

    override suspend fun invoices(principal: Principal, limit: Int, offset: Int): BillingInvoicePage {
        requireRegistered(principal)
        return ledger.invoices(principal.userId, limit.coerceIn(1, 100), offset.coerceAtLeast(0))
    }

    override suspend fun invoice(principal: Principal, invoiceId: Long): BillingInvoiceDetail {
        requireRegistered(principal)
        requirePositiveId(invoiceId, "invoiceId")
        return ledger.invoice(principal.userId, invoiceId)
            ?: throw billingError(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Invoice not found.")
    }

    override suspend fun requestRefund(
        principal: Principal,
        paymentId: Long,
        command: RequestBillingActionCommand,
    ): BillingAction {
        requireRegistered(principal)
        requirePositiveId(paymentId, "paymentId")
        validateActionCommand(command)
        return ledger.requestRefund(principal.userId, paymentId, command.normalized(), clock.instant())
    }

    override suspend fun requestCancellation(
        principal: Principal,
        originalTransactionId: String,
        command: RequestBillingActionCommand,
    ): BillingAction {
        requireRegistered(principal)
        validateProviderId(originalTransactionId, "originalTransactionId")
        validateActionCommand(command)
        return ledger.requestCancellation(
            principal.userId,
            originalTransactionId.trim(),
            command.normalized(),
            clock.instant(),
        )
    }

    override suspend fun receive(signedPayload: String) {
        validateSignedPayload(signedPayload)
        val notification = verifier.verifyNotification(signedPayload)
        val now = clock.instant()
        if (!ledger.recordAppleNotification(notification, now)) return

        try {
            val transaction = notification.transaction
            if (transaction != null) {
                validateTransaction(transaction, now)
                val userId = ledger.userIdForAppAccountToken(transaction.appAccountToken)
                    ?: throw billingError(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        ApiErrorCode.BILLING_TRANSACTION_INVALID,
                        "The App Store notification appAccountToken is unknown.",
                    )
                val product = ledger.tierProduct(transaction.productId)
                    ?: throw billingError(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        ApiErrorCode.BILLING_TRANSACTION_INVALID,
                        "The App Store notification product is not mapped to a tier.",
                    )
                if (product.productType != transaction.productType) invalidTransaction()
                val recorded = ledger.recordVerifiedPayment(
                    RecordVerifiedPaymentCommand(
                        userId = userId,
                        tierProduct = product,
                        transaction = transaction,
                        invoiceNumber = null,
                        source = BillingEventSource.APPLE_NOTIFICATION,
                        eventId = "apple-transaction:${transaction.transactionId}",
                        occurredAt = now,
                    ),
                )
                if (notification.notificationType in FULFILLMENT_NOTIFICATION_TYPES) {
                    ledger.fulfill(recorded.id, now)
                }
            }
            ledger.applyAppleNotification(ApplyAppleNotificationCommand(notification, clock.instant()))
        } catch (error: Exception) {
            ledger.markAppleNotificationFailed(
                notification.notificationUUID,
                (error.message ?: error.javaClass.name).take(4000),
                clock.instant(),
            )
            throw error
        }
    }

    override suspend fun recoverDueFulfillments(): BillingRecoveryResult {
        val now = clock.instant()
        val expiredCheckouts = ledger.expirePendingCheckouts(
            expiredBefore = now.minus(CHECKOUT_TIMEOUT),
            now = now,
            limit = CHECKOUT_EXPIRATION_BATCH_SIZE,
        )
        val claims = ledger.claimDueFulfillmentJobs(
            now = now,
            staleBefore = now.minus(FULFILLMENT_CLAIM_LEASE),
            limit = FULFILLMENT_RECOVERY_BATCH_SIZE,
        )
        var completed = 0
        var retried = 0
        var compensationRequired = 0
        for (claim in claims) {
            try {
                ledger.fulfill(claim.invoiceId, clock.instant())
                completed += 1
            } catch (error: Exception) {
                val reason = (error.message ?: error.javaClass.name).take(4000)
                if (claim.attempts + 1 >= claim.maxAttempts) {
                    ledger.requireCompensation(claim.invoiceId, reason, clock.instant())
                    compensationRequired += 1
                } else {
                    val retryAt = clock.instant().plus(fulfillmentRetryDelay(claim.attempts + 1))
                    ledger.rescheduleFulfillmentJob(claim, reason, retryAt, clock.instant())
                    retried += 1
                }
            }
        }
        return BillingRecoveryResult(
            expiredCheckouts = expiredCheckouts,
            claimed = claims.size,
            completed = completed,
            retried = retried,
            compensationRequired = compensationRequired,
        )
    }

    private fun fulfillmentRetryDelay(attempt: Int): Duration =
        Duration.ofSeconds((5L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(60))

    private fun requireRegistered(principal: Principal) {
        if (principal.anonymous || principal.status != "ACTIVE") {
            throw billingError(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.BILLING_ACCOUNT_REQUIRED,
                "Sign in to purchase or manage a membership.",
            )
        }
    }

    private suspend fun applyVerifiedTransaction(
        principal: Principal,
        transaction: VerifiedAppleTransaction,
        invoiceNumber: UUID?,
        source: BillingEventSource,
        now: Instant,
    ): BillingInvoiceSummary {
        val expectedToken = ledger.findOrCreateAppAccountToken(principal.userId, now)
        if (transaction.appAccountToken != expectedToken) {
            throw billingError(
                HttpStatus.CONFLICT,
                ApiErrorCode.BILLING_TRANSACTION_CONFLICT,
                "The App Store transaction appAccountToken does not match the signed-in user.",
            )
        }
        val product = ledger.tierProduct(transaction.productId)
            ?: throw billingError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.BILLING_TRANSACTION_INVALID,
                "The App Store product is not enabled for a BuddyStudy tier.",
            )
        if (product.productType != transaction.productType) {
            throw billingError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.BILLING_TRANSACTION_INVALID,
                "The App Store product type does not match the server tier catalog.",
            )
        }
        return verifiedPayments.apply(
            ApplyVerifiedBillingPaymentCommand(
                userId = principal.userId,
                tierProduct = product,
                transaction = transaction,
                invoiceNumber = invoiceNumber,
                source = source,
                occurredAt = now,
            ),
        )
    }

    private fun validateSignedPayload(payload: String) {
        val trimmed = payload.trim()
        if (trimmed.length !in 64..MAX_SIGNED_PAYLOAD_LENGTH || trimmed.count { it == '.' } != 2) {
            throw billingError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.BILLING_TRANSACTION_INVALID,
                "The signed App Store payload is malformed.",
            )
        }
    }

    private fun validateTransaction(transaction: VerifiedAppleTransaction, now: Instant) {
        validateProviderId(transaction.transactionId, "transactionId")
        validateProviderId(transaction.originalTransactionId, "originalTransactionId")
        if (!PRODUCT_ID.matches(transaction.productId) || transaction.quantity !in 1..100) invalidTransaction()
        if (transaction.priceMilliunits?.let { it < 0 || it > MAX_PRICE_MILLIUNITS } == true) invalidTransaction()
        if (transaction.currency?.let { !CURRENCY.matches(it) } == true) invalidTransaction()
        if (transaction.purchaseAt.isAfter(now.plus(ALLOWED_CLOCK_SKEW))) invalidTransaction()
        if (transaction.signedAt.isAfter(now.plus(ALLOWED_CLOCK_SKEW))) invalidTransaction()
        if (transaction.signedAt.isBefore(transaction.purchaseAt.minus(ALLOWED_CLOCK_SKEW))) invalidTransaction()
        if (transaction.expiresAt?.isBefore(transaction.purchaseAt) == true) invalidTransaction()
        if (!SHA256.matches(transaction.signedPayloadSha256)) invalidTransaction()
    }

    private fun validateActionCommand(command: RequestBillingActionCommand) {
        validateIdempotencyKey(command.idempotencyKey)
        if ((command.reason?.length ?: 0) > 1000) {
            throw billingError(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Reason is too long.")
        }
    }

    private fun validateIdempotencyKey(value: String) {
        if (!IDEMPOTENCY_KEY.matches(value.trim())) {
            throw billingError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "A valid idempotency key is required.",
            )
        }
    }

    private fun validateProviderId(value: String, name: String) {
        if (!PROVIDER_ID.matches(value.trim())) {
            throw billingError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "$name is invalid.",
            )
        }
    }

    private fun requirePositiveId(value: Long, name: String) {
        if (value <= 0) {
            throw billingError(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "$name must be positive.")
        }
    }

    private fun invalidTransaction(): Nothing = throw billingError(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ApiErrorCode.BILLING_TRANSACTION_INVALID,
        "The verified App Store transaction contains invalid values.",
    )

    private fun RequestBillingActionCommand.normalized() = copy(
        idempotencyKey = idempotencyKey.trim(),
        reason = reason?.trim()?.takeIf(String::isNotEmpty),
    )

    private fun billingError(status: HttpStatus, code: ApiErrorCode, message: String) =
        ApiException(status, code, message)

    private companion object {
        val CHECKOUT_TIMEOUT: Duration = Duration.ofMinutes(10)
        const val CHECKOUT_EXPIRATION_BATCH_SIZE = 100
        const val MAX_SIGNED_PAYLOAD_LENGTH = 200_000
        const val MAX_PRICE_MILLIUNITS = 100_000_000_000L
        val ALLOWED_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
        val PROVIDER_ID = Regex("^[A-Za-z0-9._:-]{1,191}$")
        val PRODUCT_ID = Regex("^[A-Za-z0-9._-]{1,191}$")
        val CURRENCY = Regex("^[A-Z]{3}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{8,191}$")
        val FULFILLMENT_NOTIFICATION_TYPES = setOf("SUBSCRIBED", "DID_RENEW", "ONE_TIME_CHARGE", "OFFER_REDEEMED")
        val FULFILLMENT_CLAIM_LEASE: Duration = Duration.ofMinutes(2)
        const val FULFILLMENT_RECOVERY_BATCH_SIZE = 25
    }
}
