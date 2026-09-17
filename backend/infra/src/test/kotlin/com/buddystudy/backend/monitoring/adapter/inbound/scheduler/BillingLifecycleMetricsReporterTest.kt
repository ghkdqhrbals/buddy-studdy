package com.buddystudy.backend.monitoring.adapter.inbound.scheduler

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.RowsFetchSpec
import reactor.core.publisher.Mono
import java.util.function.BiFunction

class BillingLifecycleMetricsReporterTest {
    private val logger = LoggerFactory.getLogger(BillingLifecycleMetricsReporter::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val registry = SimpleMeterRegistry()
    private val results = ArrayDeque<Mono<Long>>()
    private lateinit var reporter: BillingLifecycleMetricsReporter

    @BeforeEach
    fun setUp() {
        appender.start()
        logger.addAppender(appender)
        val database = mock(DatabaseClient::class.java)
        val statement = mock(DatabaseClient.GenericExecuteSpec::class.java)
        @Suppress("UNCHECKED_CAST")
        val rows = mock(RowsFetchSpec::class.java) as RowsFetchSpec<Long>
        `when`(database.sql(anyString())).thenReturn(statement)
        `when`(statement.map(any<BiFunction<Row, RowMetadata, Long>>())).thenReturn(rows)
        `when`(rows.one()).thenAnswer { results.removeFirst() }
        reporter = BillingLifecycleMetricsReporter(database, registry)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        registry.close()
    }

    @Test
    fun `healthy lifecycle snapshot does not alert`() {
        assertThat(snapshot().hasOperationalAnomaly()).isFalse()
    }

    @Test
    fun `active lifecycle anomalies alert while exhausted records remain observable`() {
        assertThat(snapshot(webhookLagSeconds = 901).hasOperationalAnomaly()).isTrue()
        assertThat(snapshot(entitlementMismatches = 1).hasOperationalAnomaly()).isTrue()
        assertThat(snapshot(exhaustedReconciliations = 1).hasOperationalAnomaly()).isFalse()
        assertThat(snapshot(staleReservations = 1).hasOperationalAnomaly()).isTrue()
        assertThat(snapshot(negativeQuotaCounters = 1).hasOperationalAnomaly()).isTrue()
        assertThat(snapshot(duplicateActiveSubscriptions = 1).hasOperationalAnomaly()).isTrue()
        assertThat(snapshot(ownershipConflicts = 1).hasOperationalAnomaly()).isTrue()
    }

    @Test
    fun `ongoing anomaly updates metrics without repeating an error`(): Unit = runBlocking {
        enqueue(snapshot(webhookLagSeconds = 901))
        reporter.report()
        enqueue(snapshot(webhookLagSeconds = 1_201))
        reporter.report()

        assertThat(events(Level.ERROR)).hasSize(1)
        assertThat(events(Level.WARN).single()).contains("billing_lifecycle_anomaly_ongoing", "webhookLagSeconds=1201")
        assertThat(events(Level.INFO).filter { it.startsWith("billing_lifecycle_metrics ") }).hasSize(2)
        assertThat(registry.get("billing.lifecycle.webhook.lag.seconds").gauge().value()).isEqualTo(1_201.0)
    }

    @Test
    fun `new anomaly kind alerts even while a different condition remains active`(): Unit = runBlocking {
        enqueue(snapshot(staleReservations = 1))
        reporter.report()
        enqueue(snapshot(staleReservations = 2, negativeQuotaCounters = 1))
        reporter.report()
        enqueue(snapshot(staleReservations = 2, negativeQuotaCounters = 1))
        reporter.report()

        assertThat(events(Level.ERROR)).hasSize(2)
        assertThat(events(Level.ERROR).last()).contains("newAnomalies=negative_quota_counters")
        assertThat(events(Level.WARN)).hasSize(1)
    }

    @Test
    fun `successful healthy snapshot allows the next occurrence to alert again`(): Unit = runBlocking {
        enqueue(snapshot(entitlementMismatches = 1))
        reporter.report()
        enqueue(snapshot())
        reporter.report()
        enqueue(snapshot(entitlementMismatches = 1))
        reporter.report()

        assertThat(events(Level.ERROR)).hasSize(2)
        assertThat(events(Level.INFO).filter { it.startsWith("billing_lifecycle_anomaly_recovered") }).hasSize(1)
    }

    @Test
    fun `persistent duplicate subscription alerts once across ten scheduled polls and alerts again after recovery`(): Unit = runBlocking {
        repeat(10) {
            enqueue(snapshot(duplicateActiveSubscriptions = 1))
            reporter.report()
        }

        assertThat(events(Level.ERROR).single()).contains("newAnomalies=duplicate_active_subscriptions")
        assertThat(events(Level.WARN)).hasSize(9)
        assertThat(events(Level.INFO).filter { it.startsWith("billing_lifecycle_metrics ") }).hasSize(10)
        assertThat(registry.get("billing.lifecycle.subscriptions.duplicate.active").gauge().value()).isEqualTo(1.0)

        enqueue(snapshot())
        reporter.report()
        enqueue(snapshot(duplicateActiveSubscriptions = 1))
        reporter.report()

        assertThat(events(Level.ERROR)).hasSize(2)
        assertThat(events(Level.INFO).filter { it.startsWith("billing_lifecycle_anomaly_recovered") }.single())
            .contains("anomalies=duplicate_active_subscriptions remainingAnomalies=none")
    }

    @Test
    fun `a resolved condition can recur while another condition persists`(): Unit = runBlocking {
        enqueue(snapshot(staleReservations = 1, negativeQuotaCounters = 1))
        reporter.report()
        enqueue(snapshot(staleReservations = 1))
        reporter.report()
        enqueue(snapshot(staleReservations = 1, negativeQuotaCounters = 1))
        reporter.report()

        assertThat(events(Level.ERROR)).hasSize(2)
        assertThat(events(Level.ERROR).last()).contains("newAnomalies=negative_quota_counters")
        assertThat(events(Level.INFO).filter { it.startsWith("billing_lifecycle_anomaly_recovered") }.single())
            .contains("anomalies=negative_quota_counters remainingAnomalies=stale_reservations")
    }

    @Test
    fun `repeated collection failures preserve anomaly state and last known metrics`(): Unit = runBlocking {
        enqueue(snapshot(staleReservations = 3))
        reporter.report()
        repeat(2) {
            results.add(Mono.error(IllegalStateException("database unavailable")))
            reporter.report()
        }

        assertThat(events(Level.ERROR)).hasSize(2)
        assertThat(events(Level.WARN).single()).startsWith("billing_lifecycle_metrics_collection_still_failed")
        assertThat(events(Level.INFO).filter { it.contains("recovered") }).isEmpty()
        assertThat(registry.get("billing.lifecycle.quota.stale.reservations").gauge().value()).isEqualTo(3.0)

        enqueue(snapshot(staleReservations = 3))
        reporter.report()
        assertThat(events(Level.ERROR)).hasSize(2)
        assertThat(events(Level.WARN).last()).startsWith("billing_lifecycle_anomaly_ongoing")
        assertThat(events(Level.INFO)).contains("billing_lifecycle_metrics_collection_recovered")
        assertThat(events(Level.INFO).filter { it.startsWith("billing_lifecycle_anomaly_recovered") }).isEmpty()

        results.add(Mono.error(IllegalStateException("database unavailable again")))
        reporter.report()
        assertThat(events(Level.ERROR)).hasSize(3)
    }

    @Test
    fun `every interval with new ownership conflicts alerts even during an ongoing anomaly`(): Unit = runBlocking {
        val conflicts = registry.counter("billing.lifecycle.ownership.conflicts")
        repeat(2) {
            conflicts.increment()
            enqueue(snapshot(staleReservations = 1))
            reporter.report()
        }
        enqueue(snapshot(staleReservations = 1))
        reporter.report()

        assertThat(events(Level.ERROR)).hasSize(2)
        assertThat(events(Level.ERROR).last()).contains("newAnomalies=ownership_conflicts", "ownershipConflicts=1")
        assertThat(events(Level.WARN).single()).contains("ownershipConflicts=0")
    }

    @Test
    fun `collection failure does not consume ownership conflict events`(): Unit = runBlocking {
        registry.counter("billing.lifecycle.ownership.conflicts").increment()
        results.add(Mono.error(IllegalStateException("database unavailable")))
        reporter.report()
        enqueue(snapshot())
        reporter.report()
        enqueue(snapshot())
        reporter.report()

        assertThat(events(Level.ERROR)).hasSize(2)
        assertThat(events(Level.ERROR).last()).contains("newAnomalies=ownership_conflicts", "ownershipConflicts=1")
        assertThat(events(Level.WARN)).isEmpty()
    }

    @Test
    fun `coroutine cancellation propagates without reporting a collection failure`() {
        results.add(Mono.error(CancellationException("scheduler stopped")))

        assertThatThrownBy { runBlocking { reporter.report() } }.isInstanceOf(CancellationException::class.java)
        assertThat(appender.list).isEmpty()

        runBlocking {
            enqueue(snapshot())
            reporter.report()
        }
        assertThat(events(Level.INFO).filter { it.contains("recovered") }).isEmpty()
    }

    private fun enqueue(snapshot: BillingLifecycleMetricsSnapshot) {
        listOf(
            snapshot.webhookLagSeconds,
            snapshot.entitlementMismatches,
            snapshot.exhaustedReconciliations,
            snapshot.staleReservations,
            snapshot.negativeQuotaCounters,
            snapshot.duplicateActiveSubscriptions,
        ).forEach { results.add(Mono.just(it)) }
    }

    private fun events(level: Level) = appender.list.filter { it.level == level }.map { it.formattedMessage }

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
