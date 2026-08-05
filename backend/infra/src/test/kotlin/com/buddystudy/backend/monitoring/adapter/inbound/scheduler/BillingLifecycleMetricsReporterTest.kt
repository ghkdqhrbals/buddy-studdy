package com.buddystudy.backend.monitoring.adapter.inbound.scheduler

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BillingLifecycleMetricsReporterTest {
    @Test
    fun `healthy lifecycle snapshot does not alert`() {
        assertThat(snapshot().hasOperationalAnomaly()).isFalse()
    }

    @Test
    fun `each durable lifecycle anomaly alerts`() {
        assertThat(snapshot(webhookLagSeconds = 901).hasOperationalAnomaly()).isTrue()
        assertThat(snapshot(entitlementMismatches = 1).hasOperationalAnomaly()).isTrue()
        assertThat(snapshot(exhaustedReconciliations = 1).hasOperationalAnomaly()).isTrue()
        assertThat(snapshot(staleReservations = 1).hasOperationalAnomaly()).isTrue()
        assertThat(snapshot(negativeQuotaCounters = 1).hasOperationalAnomaly()).isTrue()
        assertThat(snapshot(duplicateActiveSubscriptions = 1).hasOperationalAnomaly()).isTrue()
        assertThat(snapshot(ownershipConflicts = 1).hasOperationalAnomaly()).isTrue()
    }

    private fun snapshot(
        webhookLagSeconds: Long = 0,
        entitlementMismatches: Long = 0,
        exhaustedReconciliations: Long = 0,
        staleReservations: Long = 0,
        negativeQuotaCounters: Long = 0,
        duplicateActiveSubscriptions: Long = 0,
        ownershipConflicts: Long = 0,
    ) = BillingLifecycleMetricsSnapshot(
        webhookLagSeconds,
        entitlementMismatches,
        exhaustedReconciliations,
        staleReservations,
        negativeQuotaCounters,
        duplicateActiveSubscriptions,
        ownershipConflicts,
    )
}
