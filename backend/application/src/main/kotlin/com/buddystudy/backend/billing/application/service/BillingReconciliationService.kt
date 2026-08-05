package com.buddystudy.backend.billing.application.service

import com.buddystudy.backend.billing.application.port.inbound.BillingReconciliationUseCase
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatCustomerInfoPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class BillingReconciliationService(
    private val ledger: BillingLedgerPort,
    private val revenueCat: RevenueCatCustomerInfoPort,
    private val clock: Clock = Clock.systemUTC(),
) : BillingReconciliationUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun reconcileDueSubscriptions(): Int {
        val claims = ledger.claimDueSubscriptionReconciliations(clock.instant(), 25)
        claims.forEach { claim ->
            try {
                val snapshot = revenueCat.fetch(claim.appAccountToken, claim.originalTransactionId)
                ledger.applySubscriptionSnapshot(claim, snapshot, clock.instant())
            } catch (error: Exception) {
                ledger.recordSubscriptionReconcileFailure(
                    claim,
                    (error.message ?: error.javaClass.name).take(4000),
                    clock.instant(),
                )
                log.error(
                    "billing_subscription_reconcile_failed userId={} originalTransactionId={} attempt={}",
                    claim.userId,
                    claim.originalTransactionId,
                    claim.attempt,
                    error,
                )
            }
        }
        return claims.size
    }
}
