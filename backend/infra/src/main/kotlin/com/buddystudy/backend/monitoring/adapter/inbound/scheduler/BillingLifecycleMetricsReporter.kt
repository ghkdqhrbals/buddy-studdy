package com.buddystudy.backend.monitoring.adapter.inbound.scheduler

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactive.awaitSingle
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
@ConditionalOnProperty(
    prefix = "buddystudy.monitoring.billing-lifecycle",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class BillingLifecycleMetricsReporter(
    private val database: DatabaseClient,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val webhookLagSeconds = AtomicLong()
    private val entitlementMismatches = AtomicLong()
    private val exhaustedReconciliations = AtomicLong()
    private val staleReservations = AtomicLong()
    private val negativeQuotaCounters = AtomicLong()
    private val duplicateActiveSubscriptions = AtomicLong()
    private var previousOwnershipConflictCount = 0L
    private var activeAnomalies = emptySet<String>()
    private var collectionFailed = false

    init {
        register(meterRegistry, "billing.lifecycle.webhook.lag.seconds", webhookLagSeconds)
        register(meterRegistry, "billing.lifecycle.entitlement.mismatches", entitlementMismatches)
        register(meterRegistry, "billing.lifecycle.reconciliation.exhausted", exhaustedReconciliations)
        register(meterRegistry, "billing.lifecycle.quota.stale.reservations", staleReservations)
        register(meterRegistry, "billing.lifecycle.quota.negative.counters", negativeQuotaCounters)
        register(meterRegistry, "billing.lifecycle.subscriptions.duplicate.active", duplicateActiveSubscriptions)
    }

    @Scheduled(
        fixedDelayString = "\${buddystudy.monitoring.billing-lifecycle.interval-ms:300000}",
        initialDelayString = "\${buddystudy.monitoring.billing-lifecycle.initial-delay-ms:60000}",
    )
    suspend fun report() {
        try {
            val currentOwnershipConflictCount = meterRegistry.counter(OWNERSHIP_CONFLICT_METRIC).count().toLong()
            val snapshot = snapshot(
                ownershipConflicts = (currentOwnershipConflictCount - previousOwnershipConflictCount).coerceAtLeast(0),
            )
            if (collectionFailed) {
                logger.info("billing_lifecycle_metrics_collection_recovered")
                collectionFailed = false
            }
            previousOwnershipConflictCount = currentOwnershipConflictCount
            webhookLagSeconds.set(snapshot.webhookLagSeconds)
            entitlementMismatches.set(snapshot.entitlementMismatches)
            exhaustedReconciliations.set(snapshot.exhaustedReconciliations)
            staleReservations.set(snapshot.staleReservations)
            negativeQuotaCounters.set(snapshot.negativeQuotaCounters)
            duplicateActiveSubscriptions.set(snapshot.duplicateActiveSubscriptions)
            logger.info(
                "billing_lifecycle_metrics webhookLagSeconds={} entitlementMismatches={} " +
                    "exhaustedReconciliations={} staleReservations={} negativeQuotaCounters={} " +
                    "duplicateActiveSubscriptions={} ownershipConflicts={}",
                snapshot.webhookLagSeconds,
                snapshot.entitlementMismatches,
                snapshot.exhaustedReconciliations,
                snapshot.staleReservations,
                snapshot.negativeQuotaCounters,
                snapshot.duplicateActiveSubscriptions,
                snapshot.ownershipConflicts,
            )
            val anomalies = snapshot.operationalAnomalies()
            val newAnomalies = anomalies - activeAnomalies
            val recoveredAnomalies = activeAnomalies - anomalies
            if (anomalies.isNotEmpty()) {
                // Alert when a condition starts; a persistent snapshot is not a new incident.
                logger.atLevel(if (newAnomalies.isNotEmpty()) Level.ERROR else Level.WARN).log(
                    "{} anomalies={} newAnomalies={} webhookLagSeconds={} entitlementMismatches={} " +
                        "exhaustedReconciliations={} staleReservations={} negativeQuotaCounters={} " +
                        "duplicateActiveSubscriptions={} ownershipConflicts={}",
                    if (newAnomalies.isNotEmpty()) "billing_lifecycle_anomaly" else "billing_lifecycle_anomaly_ongoing",
                    anomalies.joinToString(","),
                    newAnomalies.joinToString(",").ifEmpty { "none" },
                    snapshot.webhookLagSeconds,
                    snapshot.entitlementMismatches,
                    snapshot.exhaustedReconciliations,
                    snapshot.staleReservations,
                    snapshot.negativeQuotaCounters,
                    snapshot.duplicateActiveSubscriptions,
                    snapshot.ownershipConflicts,
                )
            }
            if (recoveredAnomalies.isNotEmpty()) {
                logger.info(
                    "billing_lifecycle_anomaly_recovered anomalies={} remainingAnomalies={}",
                    recoveredAnomalies.joinToString(","),
                    anomalies.joinToString(",").ifEmpty { "none" },
                )
            }
            // Ownership conflicts are new events, not a persistent database condition.
            activeAnomalies = anomalies - "ownership_conflicts"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.atLevel(if (collectionFailed) Level.WARN else Level.ERROR).log(
                "{} errorType={} message={}",
                if (collectionFailed) "billing_lifecycle_metrics_collection_still_failed"
                else "billing_lifecycle_metrics_collection_failed",
                error.javaClass.name,
                error.message,
                error,
            )
            collectionFailed = true
        }
    }

    private suspend fun snapshot(ownershipConflicts: Long): BillingLifecycleMetricsSnapshot = BillingLifecycleMetricsSnapshot(
        webhookLagSeconds = scalar(
            """
            select coalesce(max(timestampdiff(second, occurred_at, utc_timestamp(6))), 0)
            from subscription_events
            where provider = 'REVENUECAT'
              and processing_status in ('PENDING', 'PROCESSING', 'FAILED')
              and attempt_count < max_attempts
            """.trimIndent(),
        ),
        entitlementMismatches = scalar(
            """
            select count(*) from (
                select ids.user_id,
                       coalesce(e.tier_code, 'TIER1') projected_tier,
                       coalesce((
                           select s.tier_code
                           from subscriptions s
                           join user_membership_tiers t on t.tier_code = s.tier_code
                           where s.user_id = ids.user_id
                             and s.access_status in ('ACTIVE', 'GRACE_PERIOD')
                             and (s.expires_at is null or s.expires_at > utc_timestamp(6))
                           order by t.monthly_question_limit desc, s.id desc limit 1
                       ), 'TIER1') expected_tier
                from (
                    select user_id from user_entitlement_projection
                    union
                    select user_id from subscriptions where user_id is not null
                ) ids
                left join user_entitlement_projection e on e.user_id = ids.user_id
            ) comparison where projected_tier <> expected_tier
            """.trimIndent(),
        ),
        exhaustedReconciliations = scalar(
            """
            select count(*) from subscription_events
            where provider = 'REVENUECAT'
              and event_type = 'SUBSCRIPTION_RECONCILE_FAILED'
              and processing_status = 'EXHAUSTED'
            """.trimIndent(),
        ),
        staleReservations = scalar(
            """
            select count(*) from quota_reservations
            where status = 'RESERVED' and reserved_at < timestampadd(minute, -30, utc_timestamp(6))
            """.trimIndent(),
        ),
        negativeQuotaCounters = scalar(
            """
            select count(*) from user_quota
            where committed_count < 0 or reserved_count < 0 or bonus_limit < 0 or remaining_count < 0
            """.trimIndent(),
        ),
        duplicateActiveSubscriptions = scalar(
            """
            select count(*) from (
                select user_id from subscriptions
                where user_id is not null and access_status in ('ACTIVE', 'GRACE_PERIOD')
                  and (expires_at is null or expires_at > utc_timestamp(6))
                group by user_id having count(*) > 1
            ) duplicates
            """.trimIndent(),
        ),
        ownershipConflicts = ownershipConflicts,
    )

    private suspend fun scalar(sql: String): Long = database.sql(sql)
        .map { row, _ -> row.get(0, java.lang.Long::class.java)?.toLong() ?: 0L }
        .one().awaitSingle()

    private fun register(registry: MeterRegistry, name: String, value: AtomicLong) {
        Gauge.builder(name, value) { it.get().toDouble() }.register(registry)
    }

    private companion object {
        const val OWNERSHIP_CONFLICT_METRIC = "billing.lifecycle.ownership.conflicts"
    }
}

internal data class BillingLifecycleMetricsSnapshot(
    val webhookLagSeconds: Long,
    val entitlementMismatches: Long,
    val exhaustedReconciliations: Long,
    val staleReservations: Long,
    val negativeQuotaCounters: Long,
    val duplicateActiveSubscriptions: Long,
    val ownershipConflicts: Long,
) {
    fun hasOperationalAnomaly(): Boolean = operationalAnomalies().isNotEmpty()

    fun operationalAnomalies(): Set<String> = buildSet {
        if (webhookLagSeconds > 15 * 60) add("webhook_lag")
        if (entitlementMismatches > 0) add("entitlement_mismatches")
        if (staleReservations > 0) add("stale_reservations")
        if (negativeQuotaCounters > 0) add("negative_quota_counters")
        if (duplicateActiveSubscriptions > 0) add("duplicate_active_subscriptions")
        if (ownershipConflicts > 0) add("ownership_conflicts")
    }
}
