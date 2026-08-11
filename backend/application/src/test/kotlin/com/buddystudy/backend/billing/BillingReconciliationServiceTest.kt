package com.buddystudy.backend.billing

import com.buddystudy.backend.billing.application.model.RevenueCatCustomerSnapshot
import com.buddystudy.backend.billing.application.model.BillingProcessingFailureOutcome
import com.buddystudy.backend.billing.application.model.SubscriptionReconciliationClaim
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.billing.application.port.outbound.RevenueCatCustomerInfoPort
import com.buddystudy.backend.billing.application.service.BillingReconciliationService
import com.buddystudy.billing.domain.SubscriptionAccessStatus
import com.buddystudy.billing.domain.SubscriptionRenewalStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class BillingReconciliationServiceTest {
    private val now = Instant.parse("2026-08-05T00:00:00Z")
    private val token = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val claim = SubscriptionReconciliationClaim(3, 7, "2000000123456789", token, 1)

    @Test
    fun `reconciliation requests the exact store subscription and applies its snapshot`() = runBlocking {
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val revenueCat = Mockito.mock(RevenueCatCustomerInfoPort::class.java)
        val snapshot = RevenueCatCustomerSnapshot(
            SubscriptionAccessStatus.GRACE_PERIOD,
            SubscriptionRenewalStatus.BILLING_RETRY,
            now.plusSeconds(3_600),
            now,
        )
        Mockito.`when`(ledger.claimDueSubscriptionReconciliations(now, 25)).thenReturn(listOf(claim))
        Mockito.`when`(revenueCat.fetch(token, claim.originalTransactionId)).thenReturn(snapshot)

        val count = BillingReconciliationService(
            ledger,
            revenueCat,
            Clock.fixed(now, ZoneOffset.UTC),
        ).reconcileDueSubscriptions()

        assertThat(count).isEqualTo(1)
        Mockito.verify(revenueCat).fetch(token, claim.originalTransactionId)
        Mockito.verify(ledger).applySubscriptionSnapshot(claim, snapshot, now)
    }

    @Test
    fun `user reconciliation immediately refreshes only that users subscription`() = runBlocking {
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val revenueCat = Mockito.mock(RevenueCatCustomerInfoPort::class.java)
        val snapshot = RevenueCatCustomerSnapshot(
            SubscriptionAccessStatus.ACTIVE,
            SubscriptionRenewalStatus.CANCELED,
            now.plusSeconds(86_400),
            now,
        )
        Mockito.`when`(ledger.claimUserSubscriptionReconciliations(claim.userId, now, 10))
            .thenReturn(listOf(claim))
        Mockito.`when`(revenueCat.fetch(token, claim.originalTransactionId)).thenReturn(snapshot)

        val count = BillingReconciliationService(
            ledger,
            revenueCat,
            Clock.fixed(now, ZoneOffset.UTC),
        ).reconcileUserSubscription(claim.userId)

        assertThat(count).isEqualTo(1)
        Mockito.verify(ledger).claimUserSubscriptionReconciliations(claim.userId, now, 10)
        Mockito.verify(ledger).applySubscriptionSnapshot(claim, snapshot, now)
    }

    @Test
    fun `provider failure is committed to reconciliation history without aborting the batch`() = runBlocking {
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val revenueCat = Mockito.mock(RevenueCatCustomerInfoPort::class.java)
        Mockito.`when`(ledger.claimDueSubscriptionReconciliations(now, 25)).thenReturn(listOf(claim))
        Mockito.`when`(revenueCat.fetch(token, claim.originalTransactionId))
            .thenThrow(IllegalStateException("provider unavailable"))
        Mockito.`when`(ledger.recordSubscriptionReconcileFailure(claim, "provider unavailable", now))
            .thenReturn(retryingOutcome(1))

        val count = BillingReconciliationService(
            ledger,
            revenueCat,
            Clock.fixed(now, ZoneOffset.UTC),
        ).reconcileDueSubscriptions()

        assertThat(count).isEqualTo(1)
        Mockito.verify(ledger).recordSubscriptionReconcileFailure(claim, "provider unavailable", now)
    }

    @Test
    fun `user reconciliation reports provider failure instead of returning stale status`() = runBlocking {
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val revenueCat = Mockito.mock(RevenueCatCustomerInfoPort::class.java)
        Mockito.`when`(ledger.claimUserSubscriptionReconciliations(claim.userId, now, 10))
            .thenReturn(listOf(claim))
        Mockito.`when`(revenueCat.fetch(token, claim.originalTransactionId))
            .thenThrow(IllegalStateException("provider unavailable"))
        Mockito.`when`(ledger.recordSubscriptionReconcileFailure(claim, "provider unavailable", now))
            .thenReturn(retryingOutcome(1))

        val service = BillingReconciliationService(
            ledger,
            revenueCat,
            Clock.fixed(now, ZoneOffset.UTC),
        )

        val error = runCatching { service.reconcileUserSubscription(claim.userId) }.exceptionOrNull()

        assertThat(error)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("provider unavailable")
        Mockito.verify(ledger).recordSubscriptionReconcileFailure(claim, "provider unavailable", now)
    }

    @Test
    fun `one failed subscription does not prevent later subscriptions from reconciling`() = runBlocking {
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val revenueCat = Mockito.mock(RevenueCatCustomerInfoPort::class.java)
        val second = claim.copy(subscriptionId = 4, userId = 8, originalTransactionId = "2000000123456790")
        val snapshot = RevenueCatCustomerSnapshot(
            SubscriptionAccessStatus.ACTIVE,
            SubscriptionRenewalStatus.WILL_RENEW,
            now.plusSeconds(86_400),
            now,
        )
        Mockito.`when`(ledger.claimDueSubscriptionReconciliations(now, 25)).thenReturn(listOf(claim, second))
        Mockito.`when`(revenueCat.fetch(token, claim.originalTransactionId))
            .thenThrow(IllegalStateException("first provider lookup failed"))
        Mockito.`when`(ledger.recordSubscriptionReconcileFailure(claim, "first provider lookup failed", now))
            .thenReturn(retryingOutcome(1))
        Mockito.`when`(revenueCat.fetch(token, second.originalTransactionId)).thenReturn(snapshot)

        val count = BillingReconciliationService(
            ledger,
            revenueCat,
            Clock.fixed(now, ZoneOffset.UTC),
        ).reconcileDueSubscriptions()

        assertThat(count).isEqualTo(2)
        Mockito.verify(ledger).recordSubscriptionReconcileFailure(claim, "first provider lookup failed", now)
        Mockito.verify(ledger).applySubscriptionSnapshot(second, snapshot, now)
    }

    @Test
    fun `third reconciliation failure is persisted as exhausted`() = runBlocking {
        val exhaustedClaim = claim.copy(attempt = 3)
        val ledger = Mockito.mock(BillingLedgerPort::class.java)
        val revenueCat = Mockito.mock(RevenueCatCustomerInfoPort::class.java)
        Mockito.`when`(ledger.claimDueSubscriptionReconciliations(now, 25)).thenReturn(listOf(exhaustedClaim))
        Mockito.`when`(revenueCat.fetch(token, exhaustedClaim.originalTransactionId))
            .thenThrow(IllegalStateException("provider unavailable"))
        Mockito.`when`(ledger.recordSubscriptionReconcileFailure(exhaustedClaim, "provider unavailable", now))
            .thenReturn(
                BillingProcessingFailureOutcome(
                    attemptCount = 3,
                    maxAttempts = 3,
                    status = "EXHAUSTED",
                    nextAttemptAt = null,
                    terminalTransition = true,
                ),
            )

        val count = BillingReconciliationService(
            ledger,
            revenueCat,
            Clock.fixed(now, ZoneOffset.UTC),
        ).reconcileDueSubscriptions()

        assertThat(count).isEqualTo(1)
        Mockito.verify(ledger).recordSubscriptionReconcileFailure(exhaustedClaim, "provider unavailable", now)
    }

    private fun retryingOutcome(attempt: Int) = BillingProcessingFailureOutcome(
        attemptCount = attempt,
        maxAttempts = 3,
        status = "RETRYING",
        nextAttemptAt = now.plusSeconds(900),
        terminalTransition = false,
    )
}
