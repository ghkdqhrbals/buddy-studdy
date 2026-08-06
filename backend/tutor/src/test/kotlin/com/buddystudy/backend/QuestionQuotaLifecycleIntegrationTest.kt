package com.buddystudy.backend

import com.buddystudy.backend.billing.application.port.outbound.BillingLedgerPort
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.UUID

@SpringBootTest
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.analytics.datasource.database-name=",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ],
)
class QuestionQuotaLifecycleIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var quota: QuestionMembershipPort
    @Autowired lateinit var billing: BillingLedgerPort
    @Autowired lateinit var database: DatabaseClient
    private val createdUserIds = mutableListOf<Long>()

    @AfterEach
    fun cleanUp(): Unit = runBlocking {
        createdUserIds.asReversed().forEach { userId ->
            execute("delete from quota_ledger where user_id = $userId")
            execute("delete from quota_reservations where user_id = $userId")
            execute("delete from quota_periods where user_id = $userId")
            execute("delete from quota_accounts where user_id = $userId")
            execute("delete from user_entitlement_projection where user_id = $userId")
            execute("delete from users where id = $userId")
        }
        createdUserIds.clear()
    }

    @Test
    fun `reservation commit and release are exactly once by correlation id`(): Unit = runBlocking {
        val now = Instant.now()
        val userId = insertUser(now.minusSeconds(60))
        val key = "quota-${UUID.randomUUID()}"

        assertThat(quota.reserveMonthlySystemQuestion(userId, now, key, key, now)).isTrue()
        assertThat(quota.reserveMonthlySystemQuestion(userId, now, key, key, now)).isTrue()
        assertThat(quota.quotaStatusForUser(userId, now)?.reservedCount).isEqualTo(1)

        quota.commitMonthlySystemQuestion(key, now.plusSeconds(1))
        quota.commitMonthlySystemQuestion(key, now.plusSeconds(2))
        assertThat(quota.quotaStatusForUser(userId, now)?.let { it.usedCount to it.reservedCount })
            .isEqualTo(1 to 0)

        quota.releaseMonthlySystemQuestion(userId, now, key, "rollback", now.plusSeconds(3))
        quota.releaseMonthlySystemQuestion(userId, now, key, "duplicate", now.plusSeconds(4))
        assertThat(quota.quotaStatusForUser(userId, now)?.let { it.usedCount to it.reservedCount })
            .isEqualTo(0 to 0)
        assertThat(longValue("select count(*) from quota_ledger where reservation_id = (select id from quota_reservations where reservation_key = '$key')"))
            .isEqualTo(3)
    }

    @Test
    fun `concurrent reservations never exceed the effective tier limit`(): Unit = runBlocking {
        val now = Instant.now()
        val userId = insertUser(now.minusSeconds(60))
        val accepted = coroutineScope {
            (1..40).map { index ->
                async(Dispatchers.Default) {
                    val key = "concurrent-$userId-$index"
                    quota.reserveMonthlySystemQuestion(userId, now, key, key, now)
                }
            }.awaitAll()
        }

        assertThat(accepted.count { it }).isEqualTo(30)
        val status = requireNotNull(quota.quotaStatusForUser(userId, now))
        assertThat(status.reservedCount).isEqualTo(30)
        assertThat(status.usedCount + status.reservedCount).isEqualTo(status.monthlyQuestionLimit)
    }

    @Test
    fun `duplicate correlation id cannot allocate a second quota slot`(): Unit = runBlocking {
        val now = Instant.now()
        val userId = insertUser(now.minusSeconds(60))
        val correlationId = "correlation-${UUID.randomUUID()}"

        assertThat(quota.reserveMonthlySystemQuestion(userId, now, "reservation-a", correlationId, now)).isTrue()
        assertThat(quota.reserveMonthlySystemQuestion(userId, now, "reservation-b", correlationId, now)).isTrue()

        val status = requireNotNull(quota.quotaStatusForUser(userId, now))
        assertThat(status.reservedCount).isEqualTo(1)
        assertThat(longValue("select count(*) from quota_reservations where correlation_id = '$correlationId'"))
            .isEqualTo(1)
        assertThat(longValue("select count(*) from quota_ledger where ledger_type = 'RESERVE' and user_id = $userId"))
            .isEqualTo(1)
    }

    @Test
    fun `reservation crossing a period boundary settles in its original period`(): Unit = runBlocking {
        val anchor = Instant.parse("2026-01-15T00:00:00Z")
        val reservedAt = Instant.parse("2026-02-14T23:59:59Z")
        val committedAt = Instant.parse("2026-02-15T00:00:01Z")
        val userId = insertUser(anchor)
        val key = "boundary-${UUID.randomUUID()}"

        assertThat(quota.reserveMonthlySystemQuestion(userId, reservedAt, key, key, reservedAt)).isTrue()
        quota.commitMonthlySystemQuestion(key, committedAt)

        val originalPeriod = requireNotNull(quota.quotaStatusForUser(userId, reservedAt))
        val newPeriod = requireNotNull(quota.quotaStatusForUser(userId, committedAt))
        assertThat(originalPeriod.usedCount).isEqualTo(1)
        assertThat(originalPeriod.reservedCount).isZero()
        assertThat(newPeriod.usedCount).isZero()
        assertThat(newPeriod.reservedCount).isZero()
    }

    @Test
    fun `tier upgrade and downgrade change only the limit and preserve usage`(): Unit = runBlocking {
        val now = Instant.now()
        val userId = insertUser(now.minusSeconds(60))
        repeat(30) { index ->
            val key = "tier-transition-$userId-$index"
            assertThat(quota.reserveMonthlySystemQuestion(userId, now, key, key, now)).isTrue()
            quota.commitMonthlySystemQuestion(key, now.plusSeconds(1))
        }

        val free = requireNotNull(quota.quotaStatusForUser(userId, now))
        assertThat(free.usedCount).isEqualTo(30)
        assertThat(remaining(free)).isZero()

        upsertEntitlement(userId, "TIER2", now)
        val upgraded = requireNotNull(quota.quotaStatusForUser(userId, now))
        assertThat(upgraded.usedCount).isEqualTo(30)
        assertThat(upgraded.baseLimit).isEqualTo(300)
        assertThat(remaining(upgraded)).isEqualTo(270)

        upsertEntitlement(userId, "TIER1", now.plusSeconds(2))
        val downgraded = requireNotNull(quota.quotaStatusForUser(userId, now.plusSeconds(2)))
        assertThat(downgraded.usedCount).isEqualTo(30)
        assertThat(downgraded.baseLimit).isEqualTo(30)
        assertThat(remaining(downgraded)).isZero()
    }

    @Test
    fun `expired paid projection cannot grant paid question quota`(): Unit = runBlocking {
        val now = Instant.now()
        val userId = insertUser(now.minusSeconds(60))
        database.sql(
            """
            insert into user_entitlement_projection (
                user_id, subscription_id, tier_code, source, access_status, renewal_status,
                product_id, started_at, expires_at, will_renew, projected_at, version
            ) values (
                :userId, null, 'TIER3', 'APP_STORE', 'ACTIVE', 'WILL_RENEW',
                'io.github.ghkdqhrbals.StudyMate.tier3.monthly', :startedAt, :expiresAt, true, :now, 0
            )
            """.trimIndent(),
        ).bind("userId", userId).bind("startedAt", now.minusSeconds(3_600))
            .bind("expiresAt", now.minusSeconds(1)).bind("now", now)
            .fetch().rowsUpdated().awaitSingle()

        val status = requireNotNull(quota.quotaStatusForUser(userId, now))

        assertThat(status.tierCode).isEqualTo("TIER1")
        assertThat(status.baseLimit).isEqualTo(30)
    }

    @Test
    fun `bonus adjustments are idempotent clamped and expire at the next period`(): Unit = runBlocking {
        val now = Instant.now()
        val userId = insertUser(now.minusSeconds(60))
        val materializeKey = "materialize-${UUID.randomUUID()}"
        assertThat(quota.reserveMonthlySystemQuestion(userId, now, materializeKey, materializeKey, now)).isTrue()
        quota.releaseMonthlySystemQuestion(userId, now, materializeKey, "test", now.plusSeconds(1))

        val first = billing.adminAdjustQuota(userId, 20, "support bonus", "bonus-once-$userId", now.plusSeconds(2))
        val duplicate = billing.adminAdjustQuota(userId, 20, "support bonus", "bonus-once-$userId", now.plusSeconds(3))
        assertThat(duplicate).isEqualTo(first)
        assertThat(requireNotNull(quota.quotaStatusForUser(userId, now)).bonusLimit).isEqualTo(20)

        val revoke = billing.adminAdjustQuota(userId, -100, "revoke bonus", "bonus-revoke-$userId", now.plusSeconds(4))
        assertThat(revoke.bonusDelta).isEqualTo(-20)
        assertThat(requireNotNull(quota.quotaStatusForUser(userId, now)).bonusLimit).isZero()

        billing.adminAdjustQuota(userId, 10, "period bonus", "bonus-next-$userId", now.plusSeconds(5))
        val current = requireNotNull(quota.quotaStatusForUser(userId, now))
        val next = requireNotNull(quota.quotaStatusForUser(userId, requireNotNull(current.resetAt).plusSeconds(1)))
        assertThat(current.bonusLimit).isEqualTo(10)
        assertThat(next.bonusLimit).isZero()
    }

    private suspend fun insertUser(createdAt: Instant): Long {
        val suffix = UUID.randomUUID().toString()
        val userId = database.sql(
            """
            insert into users (provider, provider_id, password_hash, status, email, display_name, created_at, updated_at)
            values ('EMAIL', :providerId, 'hash', 'ACTIVE', :email, :displayName, :createdAt, :createdAt)
            """.trimIndent(),
        ).bind("providerId", "quota-$suffix").bind("email", "$suffix@example.com")
            .bind("displayName", "Quota-$suffix").bind("createdAt", createdAt)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        createdUserIds += userId
        return userId
    }

    private suspend fun upsertEntitlement(userId: Long, tierCode: String, now: Instant) {
        database.sql(
            """
            insert into user_entitlement_projection (
                user_id, subscription_id, tier_code, source, access_status, renewal_status,
                will_renew, projected_at, version
            ) values (:userId, null, :tierCode, 'FREE', 'ACTIVE', 'NOT_APPLICABLE', false, :now, 0)
            on duplicate key update tier_code = values(tier_code), projected_at = values(projected_at),
                version = version + 1
            """.trimIndent(),
        ).bind("userId", userId).bind("tierCode", tierCode).bind("now", now)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun execute(sql: String) {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
    }

    private fun remaining(status: com.buddystudy.backend.study.application.port.outbound.QuestionQuotaStatus): Int =
        (status.monthlyQuestionLimit - status.usedCount - status.reservedCount).coerceAtLeast(0)

    private suspend fun longValue(sql: String): Long = database.sql(sql)
        .map { row -> row.get(0, java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
}
