package com.buddystudy.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.billing.application.model.RevenueCatCustomerSnapshot
import com.buddystudy.backend.billing.application.model.SubscriptionReconciliationClaim
import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.monitoring.adapter.inbound.scheduler.BillingLifecycleMetricsReporter
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.billing.domain.SubscriptionAccessStatus
import com.buddystudy.billing.domain.SubscriptionRenewalStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@TestPropertySource(properties = [
    "buddystudy.scheduler.enabled=false", "buddystudy.streams.enabled=false",
    "buddystudy.monitoring.billing-lifecycle.enabled=false",
    "buddystudy.analytics.datasource.database-name=", "buddystudy.crypto.master-key=test-master-key",
    "buddystudy.auth.jwt-secret=test-jwt-secret",
])
class BillingLifecycleMetricsIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var database: DatabaseClient
    @Autowired lateinit var users: UserPort
    @Autowired lateinit var memberships: QuestionMembershipPort
    @Autowired lateinit var ledger: BillingLedgerPort

    private val registry = SimpleMeterRegistry()
    private val logger = LoggerFactory.getLogger(BillingLifecycleMetricsReporter::class.java) as Logger
    private val events = ListAppender<ILoggingEvent>()
    private val userIds = mutableListOf<Long>()
    private val past = Instant.now().minusSeconds(3_600)
    private val future = Instant.now().plusSeconds(3_600)

    @BeforeEach
    fun captureLogs() {
        events.start()
        logger.addAppender(events)
    }

    @AfterEach
    fun cleanup(): Unit = runBlocking {
        logger.detachAppender(events)
        events.stop()
        registry.close()
        userIds.forEach { userId ->
            listOf("user_quota_history", "quota_reservations", "user_quota", "subscription_events",
                "user_entitlement_projection", "subscriptions", "billing_accounts", "users").forEach { table ->
                val key = if (table == "users") "id" else "user_id"
                database.sql("delete from $table where $key = :id").bind("id", userId)
                    .fetch().rowsUpdated().awaitSingle()
            }
        }
    }

    @Test
    fun `expired active projection and subscription are effectively free before reconciliation`(): Unit = runBlocking {
        val userId = fixture(subscriptionExpiresAt = past, projectionExpiresAt = past)
        assertThat(memberships.activePlanForUser(userId)?.tierCode).isEqualTo("TIER1")

        assertMismatches(0)

        // Monitoring must neither rewrite the historical projection nor repair billing data.
        assertThat(database.sql("select tier_code from user_entitlement_projection where user_id = :id")
            .bind("id", userId).map { row, _ -> row.get("tier_code", String::class.java)!! }
            .one().awaitSingle()).isEqualTo("TIER3")
    }

    @Test
    fun `unexpired and nonexpiring grace entitlements stay consistent`(): Unit = runBlocking {
        val userId = fixture(subscriptionStatus = "GRACE_PERIOD", projectionStatus = "GRACE_PERIOD")
        fixture(subscriptionStatus = "GRACE_PERIOD", subscriptionExpiresAt = null,
            projectionStatus = "GRACE_PERIOD", projectionExpiresAt = null)
        assertThat(memberships.activePlanForUser(userId)?.tierCode).isEqualTo("TIER3")

        assertMismatches(0)
    }

    @Test
    fun `renewed active subscription with expired projection remains a real mismatch`(): Unit = runBlocking {
        val userId = fixture(subscriptionExpiresAt = future, projectionExpiresAt = past)
        assertThat(memberships.activePlanForUser(userId)?.tierCode).isEqualTo("TIER1")

        assertMismatches(1)
    }

    @Test
    fun `missing free and wrong tier projections still report active subscription drift`(): Unit = runBlocking {
        fixture(projectionTier = null)
        fixture(projectionTier = "TIER1")
        fixture(projectionTier = "TIER2")

        assertMismatches(3)
    }

    @Test
    fun `still effective paid projection without a granting subscription remains a mismatch`(): Unit = runBlocking {
        fixture(subscriptionStatus = "EXPIRED", subscriptionExpiresAt = past, projectionExpiresAt = future)

        assertMismatches(1)
    }

    @Test
    fun `metric agrees with the existing expired grace reconciliation result`(): Unit = runBlocking {
        val userId = fixture(subscriptionStatus = "GRACE_PERIOD", subscriptionExpiresAt = past,
            projectionStatus = "GRACE_PERIOD", projectionExpiresAt = past)
        val claim = database.sql("""
            select s.id, s.original_transaction_id, a.app_account_token from subscriptions s
            join billing_accounts a on a.id = s.billing_account_id where s.user_id = :userId
        """.trimIndent()).bind("userId", userId).map { row, _ ->
            SubscriptionReconciliationClaim(row.get("id", java.lang.Long::class.java)!!.toLong(),
                userId, row.get("original_transaction_id", String::class.java)!!,
                UUID.fromString(row.get("app_account_token", String::class.java)), 1)
        }.one().awaitSingle()
        val now = Instant.now()
        ledger.applySubscriptionSnapshot(claim, RevenueCatCustomerSnapshot(
            SubscriptionAccessStatus.GRACE_PERIOD, SubscriptionRenewalStatus.WILL_RENEW, past, now,
        ), now)

        // Preserve the writer's current expiry contract; broadening GRACE in monitoring alone
        // would report a permanent mismatch after an otherwise successful reconciliation.
        assertThat(ledger.entitlementForUser(userId)?.tierCode).isEqualTo("TIER1")
        assertThat(memberships.activePlanForUser(userId)?.tierCode).isEqualTo("TIER1")
        assertMismatches(0)
    }

    @Test
    fun `unexpired nonexpiring and cancelled current-period entitlements stay consistent`(): Unit = runBlocking {
        fixture()
        fixture(subscriptionExpiresAt = null, projectionExpiresAt = null)
        fixture(renewalStatus = "CANCELED")

        assertMismatches(0)
    }

    private suspend fun assertMismatches(expected: Int) {
        BillingLifecycleMetricsReporter(database, registry).report()
        val messages = events.list.map { it.formattedMessage }
        assertThat(messages).noneMatch { it.startsWith("billing_lifecycle_metrics_collection_") }
        assertThat(messages).anyMatch { it.startsWith("billing_lifecycle_metrics ") }
        assertThat(registry.get("billing.lifecycle.entitlement.mismatches").gauge().value())
            .isEqualTo(expected.toDouble())
    }

    private suspend fun fixture(
        subscriptionStatus: String = "ACTIVE",
        subscriptionExpiresAt: Instant? = future,
        projectionTier: String? = "TIER3",
        projectionStatus: String = "ACTIVE",
        projectionExpiresAt: Instant? = future,
        renewalStatus: String = "WILL_RENEW",
    ): Long {
        val unique = UUID.randomUUID().toString()
        val userId = users.save(UserEntity(provider = UserProvider.EMAIL, providerId = unique,
            status = UserStatus.ACTIVE, email = "$unique@example.invalid", displayName = "Metrics ${unique.take(12)}")).id
        userIds += userId
        database.sql("""
            insert into billing_accounts (user_id, app_account_token, status, created_at, updated_at)
            values (:userId, :token, 'ACTIVE', utc_timestamp(6), utc_timestamp(6))
        """.trimIndent()).bind("userId", userId).bind("token", unique).fetch().rowsUpdated().awaitSingle()
        database.sql("""
            insert into subscriptions (billing_account_id, user_id, provider, original_transaction_id,
                tier_code, access_status, renewal_status, expires_at, created_at, updated_at)
            select id, :userId, 'APPLE', :transactionId, 'TIER3', :status, :renewal, :expires,
                utc_timestamp(6), utc_timestamp(6) from billing_accounts where user_id = :userId
        """.trimIndent()).bind("userId", userId).bind("transactionId", unique).bind("status", subscriptionStatus)
            .bind("renewal", renewalStatus).optionalInstant("expires", subscriptionExpiresAt)
            .fetch().rowsUpdated().awaitSingle()
        if (projectionTier != null) {
            database.sql("""
                insert into user_entitlement_projection (user_id, subscription_id, tier_code, source,
                    access_status, renewal_status, expires_at, projected_at)
                select :userId, id, :tier, :source, :status, :renewal, :expires, utc_timestamp(6)
                from subscriptions where user_id = :userId
            """.trimIndent()).bind("userId", userId).bind("tier", projectionTier)
                .bind("source", if (projectionTier == "TIER1") "FREE" else "APP_STORE")
                .bind("status", projectionStatus).bind("renewal", renewalStatus)
                .optionalInstant("expires", projectionExpiresAt).fetch().rowsUpdated().awaitSingle()
        }
        return userId
    }

    private fun DatabaseClient.GenericExecuteSpec.optionalInstant(name: String, value: Instant?) =
        if (value == null) bindNull(name, LocalDateTime::class.java)
        else bind(name, LocalDateTime.ofInstant(value, ZoneOffset.UTC))
}
