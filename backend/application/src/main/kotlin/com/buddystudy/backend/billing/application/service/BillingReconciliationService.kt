package com.buddystudy.backend.billing.application.service

import com.buddystudy.backend.billing.application.model.SubscriptionReconciliationClaim
import com.buddystudy.backend.billing.application.port.inbound.BillingReconciliationUseCase
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatCustomerInfoPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
class BillingReconciliationService(
    private val ledger: BillingLedgerPort,
    private val revenueCat: RevenueCatCustomerInfoPort,
    private val clock: Clock = Clock.systemUTC(),
) : BillingReconciliationUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun reconcileDueSubscriptions(): Int {
        val now = clock.instant()
        return reconcile(ledger.claimDueSubscriptionReconciliations(now, 25), now, propagateFailure = false)
    }

    override suspend fun reconcileUserSubscription(userId: Long): Int {
        val now = clock.instant()
        return reconcile(ledger.claimUserSubscriptionReconciliations(userId, now, 10), now, propagateFailure = true)
    }

    private suspend fun reconcile(
        claims: List<SubscriptionReconciliationClaim>,
        now: Instant,
        propagateFailure: Boolean,
    ): Int {
        var firstFailure: Exception? = null
        claims.forEach { claim ->
            try {
                val snapshot = revenueCat.fetch(claim.appAccountToken, claim.originalTransactionId)
                ledger.applySubscriptionSnapshot(claim, snapshot, now)
            } catch (error: Exception) {
                val outcome = ledger.recordSubscriptionReconcileFailure(
                    claim,
                    (error.message ?: error.javaClass.name).take(4000),
                    now,
                )
                if (outcome.terminalTransition) {
                    log.error(
                        "billing_processing_exhausted source=SUBSCRIPTION_RECONCILIATION userId={} " +
                            "originalTransactionId={} attempt={} maxAttempts={} errorType={} message={}",
                        claim.userId,
                        claim.originalTransactionId,
                        outcome.attemptCount,
                        outcome.maxAttempts,
                        error.javaClass.name,
                        error.message,
                        error,
                    )
                } else {
                    log.warn(
                        "billing_processing_retry_scheduled source=SUBSCRIPTION_RECONCILIATION userId={} " +
                            "originalTransactionId={} attempt={} maxAttempts={} nextAttemptAt={} errorType={} message={}",
                        claim.userId,
                        claim.originalTransactionId,
                        outcome.attemptCount,
                        outcome.maxAttempts,
                        outcome.nextAttemptAt,
                        error.javaClass.name,
                        error.message,
                    )
                }
                if (propagateFailure && firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
        return claims.size
    }
}
