package com.buddystudy.backend

import com.buddystudy.backend.study.application.port.inbound.QuestionQuotaRolloverUseCase
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
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.quota.rollover.enabled=false",
        "buddystudy.analytics.datasource.database-name=",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ],
)
class QuestionQuotaLifecycleIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var quota: QuestionMembershipPort
    @Autowired lateinit var rollovers: QuestionQuotaRolloverUseCase
    @Autowired lateinit var database: DatabaseClient
    private val createdUserIds = mutableListOf<Long>()

    @AfterEach
    fun cleanUp(): Unit = runBlocking {
        createdUserIds.asReversed().forEach { userId ->
            execute("delete from user_quota_history where user_id = $userId")
            execute("delete from quota_reservations where user_id = $userId")
            execute("delete from user_quota where user_id = $userId")
            execute("delete from quota_ledger where user_id = $userId")
            execute("delete from quota_periods where user_id = $userId")
            execute("delete from quota_accounts where user_id = $userId")
            execute("delete from user_entitlement_projection where user_id = $userId")
            execute("delete from users where id = $userId")
        }
        createdUserIds.clear()
    }

    @Test
    fun `reserve commit and release update the current row with one ordered history event each`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-01T12:00:00Z")
        val userId = insertUser(Instant.parse("2026-07-15T12:00:00Z"))
        val key = "atomic-${UUID.randomUUID()}"

        assertThat(quota.reserveMonthlySystemQuestion(userId, now, key, key, now)).isTrue()
        val reserved = quotaRow(userId)
        assertThat(reserved.committedCount).isZero()
        assertThat(reserved.reservedCount).isEqualTo(1)
        assertThat(reserved.remainingCount).isEqualTo(29)
        assertThat(reserved.version).isEqualTo(1)

        quota.commitMonthlySystemQuestion(key, now.plusSeconds(1))
        quota.commitMonthlySystemQuestion(key, now.plusSeconds(2))
        val committed = quotaRow(userId)
        assertThat(committed.committedCount).isEqualTo(1)
        assertThat(committed.reservedCount).isZero()
        assertThat(committed.version).isEqualTo(2)

        quota.releaseMonthlySystemQuestion(userId, now, key, "generation rolled back", now.plusSeconds(3))
        quota.releaseMonthlySystemQuestion(userId, now, key, "duplicate release", now.plusSeconds(4))
        val released = quotaRow(userId)
        assertThat(released.committedCount).isZero()
        assertThat(released.reservedCount).isZero()
        assertThat(released.remainingCount).isEqualTo(30)
        assertThat(released.version).isEqualTo(3)

        assertThat(stringValue("select status from quota_reservations where reservation_key = '$key'"))
            .isEqualTo("RELEASED")
        assertThat(stringValue("select group_concat(event_type order by id) from user_quota_history where reservation_id = (select id from quota_reservations where reservation_key = '$key')"))
            .isEqualTo("RESERVED,COMMITTED,RELEASED")
        assertThat(stringValue("select group_concat(quota_version_after order by quota_version_after) from user_quota_history where user_id = $userId"))
            .isEqualTo("0,1,2,3")
        assertThat(longValue("select count(*) from user_quota_history where event_id = 'commit:$key'"))
            .isEqualTo(1)
        assertThat(longValue("select count(*) from user_quota_history where event_id = 'release:$key'"))
            .isEqualTo(1)
    }

    @Test
    fun `concurrent retries with the same key and correlation return one canonical reservation`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-01T12:00:00Z")
        val userId = insertUser(Instant.parse("2026-07-15T12:00:00Z"))
        val correlationId = "same-correlation-${UUID.randomUUID()}"
        val reservationKey = "same-reservation-${UUID.randomUUID()}"

        val outcomes = coroutineScope {
            (1..12).map {
                async(Dispatchers.IO) {
                    runCatching {
                        quota.reserveMonthlySystemQuestion(
                            userId,
                            now,
                            reservationKey,
                            correlationId,
                            now,
                        )
                    }
                }
            }.awaitAll()
        }
        reportConcurrencyFailures("same-correlation", outcomes)
        assertThat(outcomes.mapNotNull { it.exceptionOrNull() }).isEmpty()
        val accepted = outcomes.map { it.getOrThrow() }

        assertThat(accepted).allMatch { it }
        assertThat(quotaRow(userId).reservedCount).isEqualTo(1)
        assertThat(longValue("select count(*) from quota_reservations where correlation_id = '$correlationId'"))
            .isEqualTo(1)
        assertThat(longValue("select count(*) from user_quota_history where user_id = $userId and event_type = 'RESERVED'"))
            .isEqualTo(1)
    }

    @Test
    fun `reusing a correlation id with different keys accepts only the canonical request`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-01T12:00:00Z")
        val userId = insertUser(Instant.parse("2026-07-15T12:00:00Z"))
        val correlationId = "conflicting-correlation-${UUID.randomUUID()}"

        val outcomes = coroutineScope {
            (1..12).map { index ->
                async(Dispatchers.IO) {
                    runCatching {
                        quota.reserveMonthlySystemQuestion(
                            userId,
                            now,
                            "conflicting-key-$correlationId-$index",
                            correlationId,
                            now,
                        )
                    }
                }
            }.awaitAll()
        }
        reportConcurrencyFailures("conflicting-correlation", outcomes)
        assertThat(outcomes.mapNotNull { it.exceptionOrNull() }).isEmpty()
        val accepted = outcomes.map { it.getOrThrow() }

        assertThat(accepted.count { it }).isEqualTo(1)
        assertThat(accepted.count { !it }).isEqualTo(11)
        assertThat(quotaRow(userId).reservedCount).isEqualTo(1)
        assertThat(longValue("select count(*) from quota_reservations where correlation_id = '$correlationId'"))
            .isEqualTo(1)
        assertThat(longValue("select count(*) from user_quota_history where user_id = $userId and event_type = 'RESERVED'"))
            .isEqualTo(1)
    }

    @Test
    fun `concurrent reservations never exceed the effective tier limit`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-01T12:00:00Z")
        val userId = insertUser(Instant.parse("2026-07-15T12:00:00Z"))
        val outcomes = coroutineScope {
            (1..40).map { index ->
                async(Dispatchers.IO) {
                    runCatching {
                        val key = "concurrent-$userId-$index"
                        quota.reserveMonthlySystemQuestion(userId, now, key, key, now)
                    }
                }
            }.awaitAll()
        }
        reportConcurrencyFailures("tier-cap", outcomes)
        assertThat(outcomes.mapNotNull { it.exceptionOrNull() }).isEmpty()
        val accepted = outcomes.map { it.getOrThrow() }

        assertThat(accepted.count { it }).isEqualTo(30)
        val row = quotaRow(userId)
        assertThat(row.committedCount).isZero()
        assertThat(row.reservedCount).isEqualTo(30)
        assertThat(row.remainingCount).isZero()
        assertThat(row.version).isEqualTo(30)
        assertThat(longValue("select count(*) from user_quota_history where user_id = $userId and event_type = 'RESERVED'"))
            .isEqualTo(30)
    }

    @Test
    fun `quota read performs one natural rollover when the scheduler has not run`(): Unit = runBlocking {
        val anchor = Instant.parse("2026-01-31T10:15:00Z")
        val beforeBoundary = Instant.parse("2026-02-28T10:14:59Z")
        val boundary = Instant.parse("2026-02-28T10:15:00Z")
        val userId = insertUser(anchor)
        val key = "fallback-${UUID.randomUUID()}"

        assertThat(quota.reserveMonthlySystemQuestion(userId, beforeBoundary, key, key, beforeBoundary)).isTrue()
        quota.commitMonthlySystemQuestion(key, beforeBoundary)

        val rolled = requireNotNull(quota.quotaStatusForUser(userId, boundary))
        val repeated = requireNotNull(quota.quotaStatusForUser(userId, boundary.plusSeconds(1)))

        assertThat(rolled.periodStartedAt).isEqualTo(boundary)
        assertThat(rolled.resetAt).isEqualTo(Instant.parse("2026-03-31T10:15:00Z"))
        assertThat(rolled.usedCount).isZero()
        assertThat(rolled.reservedCount).isZero()
        assertThat(repeated).isEqualTo(rolled)
        assertThat(longValue("select count(*) from user_quota_history where user_id = $userId and event_type = 'PERIOD_RESET'"))
            .isEqualTo(1)
        assertThat(longValue("select committed_delta from user_quota_history where user_id = $userId and event_type = 'PERIOD_RESET'"))
            .isEqualTo(-1)
    }

    @Test
    fun `scheduled rollover scans due rows once and records the reset atomically`(): Unit = runBlocking {
        val anchor = Instant.parse("2026-04-07T08:30:00Z")
        val beforeBoundary = Instant.parse("2026-05-07T08:29:59Z")
        val boundary = Instant.parse("2026-05-07T08:30:00Z")
        val userId = insertUser(anchor)
        val key = "scheduled-${UUID.randomUUID()}"

        assertThat(quota.reserveMonthlySystemQuestion(userId, beforeBoundary, key, key, beforeBoundary)).isTrue()
        val versionBefore = quotaRow(userId).version

        assertThat(rollovers.rolloverDue(boundary).rolledOver).isGreaterThanOrEqualTo(1)
        rollovers.rolloverDue(boundary.plusSeconds(1))

        val rolledOver = quotaRow(userId)
        assertThat(rolledOver.periodStartedAt).isEqualTo(boundary)
        assertThat(rolledOver.periodEndsAt).isEqualTo(Instant.parse("2026-06-07T08:30:00Z"))
        assertThat(rolledOver.committedCount).isZero()
        assertThat(rolledOver.reservedCount).isZero()
        assertThat(rolledOver.version).isEqualTo(versionBefore + 1)
        assertThat(longValue("select count(*) from user_quota_history where user_id = $userId and event_type = 'PERIOD_RESET'"))
            .isEqualTo(1)
        assertThat(longValue("select count(*) from user_quota_history where user_id = $userId and event_type = 'PERIOD_RESET' and quota_version_after = ${versionBefore + 1}"))
            .isEqualTo(1)
    }

    @Test
    fun `old period reservation settlement never mutates the new current row`(): Unit = runBlocking {
        val anchor = Instant.parse("2026-01-15T00:00:00Z")
        val reservedAt = Instant.parse("2026-02-14T23:59:59Z")
        val boundary = Instant.parse("2026-02-15T00:00:00Z")
        val userId = insertUser(anchor)
        val commitKey = "old-commit-${UUID.randomUUID()}"
        val releaseKey = "old-release-${UUID.randomUUID()}"

        assertThat(quota.reserveMonthlySystemQuestion(userId, reservedAt, commitKey, commitKey, reservedAt)).isTrue()
        assertThat(quota.reserveMonthlySystemQuestion(userId, reservedAt, releaseKey, releaseKey, reservedAt)).isTrue()
        assertThat(rollovers.rolloverUserIfDue(userId, boundary)).isTrue()
        val newPeriodBeforeSettlement = quotaRow(userId)

        quota.commitMonthlySystemQuestion(commitKey, boundary.plusSeconds(1))
        quota.releaseMonthlySystemQuestion(userId, anchor, releaseKey, "old period failed", boundary.plusSeconds(2))

        assertThat(quotaRow(userId)).isEqualTo(newPeriodBeforeSettlement)
        assertThat(stringValue("select status from quota_reservations where reservation_key = '$commitKey'"))
            .isEqualTo("COMMITTED")
        assertThat(stringValue("select status from quota_reservations where reservation_key = '$releaseKey'"))
            .isEqualTo("RELEASED")
        assertThat(longValue("select count(*) from user_quota_history where event_id in ('commit:$commitKey', 'release:$releaseKey') and applied_to_current = false and quota_version_after is null"))
            .isEqualTo(2)
        assertThat(longValue("select committed_delta from user_quota_history where event_id = 'commit:$commitKey'"))
            .isEqualTo(1)
        assertThat(longValue("select reserved_delta from user_quota_history where event_id = 'release:$releaseKey'"))
            .isEqualTo(-1)
    }

    @Test
    fun `effective tier changes preserve current period usage and downgrade clamps remaining`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-01T12:00:00Z")
        val userId = insertUser(Instant.parse("2026-07-15T12:00:00Z"))

        repeat(30) { index ->
            val key = "tier-$userId-$index"
            assertThat(quota.reserveMonthlySystemQuestion(userId, now, key, key, now)).isTrue()
            quota.commitMonthlySystemQuestion(key, now.plusSeconds(1))
        }
        val free = requireNotNull(quota.quotaStatusForUser(userId, now))

        upsertEntitlement(userId, "TIER2", now.plusSeconds(2))
        val upgraded = requireNotNull(quota.quotaStatusForUser(userId, now.plusSeconds(2)))
        val inFlightKey = "tier-in-flight-${UUID.randomUUID()}"
        assertThat(quota.reserveMonthlySystemQuestion(userId, now, inFlightKey, inFlightKey, now.plusSeconds(3))).isTrue()

        upsertEntitlement(userId, "TIER1", now.plusSeconds(4))
        val downgraded = requireNotNull(quota.quotaStatusForUser(userId, now.plusSeconds(4)))
        requireNotNull(quota.quotaStatusForUser(userId, now.plusSeconds(5)))

        assertThat(free.usedCount).isEqualTo(30)
        assertThat(upgraded.periodStartedAt).isEqualTo(free.periodStartedAt)
        assertThat(upgraded.usedCount).isEqualTo(30)
        assertThat(upgraded.baseLimit).isEqualTo(300)
        assertThat(upgraded.monthlyQuestionLimit - upgraded.usedCount - upgraded.reservedCount).isEqualTo(270)
        assertThat(downgraded.periodStartedAt).isEqualTo(free.periodStartedAt)
        assertThat(downgraded.usedCount).isEqualTo(30)
        assertThat(downgraded.reservedCount).isEqualTo(1)
        assertThat(downgraded.baseLimit).isEqualTo(30)
        assertThat(remaining(downgraded)).isZero()
        assertThat(longValue("select count(*) from user_quota_history where user_id = $userId and event_type = 'PLAN_UPGRADED'"))
            .isEqualTo(1)
        assertThat(longValue("select count(*) from user_quota_history where user_id = $userId and event_type = 'PLAN_DOWNGRADED'"))
            .isEqualTo(1)
        assertThat(longValue("select count(*) from user_quota_history where user_id = $userId and event_type in ('PLAN_UPGRADED', 'PLAN_DOWNGRADED') and (committed_delta <> 0 or reserved_delta <> 0 or bonus_delta <> 0)"))
            .isZero()
    }

    private suspend fun insertUser(createdAt: Instant): Long {
        val suffix = UUID.randomUUID().toString()
        val userId = database.sql(
            """
            insert into users (provider, provider_id, password_hash, status, email, display_name, created_at, updated_at)
            values ('EMAIL', :providerId, 'hash', 'ACTIVE', :email, :displayName, :createdAt, :createdAt)
            """.trimIndent(),
        ).bind("providerId", "quota-$suffix").bind("email", "$suffix@example.com")
            .bind("displayName", "Quota-$suffix").bind("createdAt", createdAt.utc())
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
                product_id, started_at, expires_at, will_renew, projected_at, version
            ) values (
                :userId, null, :tierCode, 'APP_STORE', 'ACTIVE', 'WILL_RENEW',
                :productId, :now, :expiresAt, true, :now, 0
            )
            on duplicate key update tier_code = values(tier_code), product_id = values(product_id),
                started_at = values(started_at), expires_at = values(expires_at),
                projected_at = values(projected_at), version = version + 1
            """.trimIndent(),
        ).bind("userId", userId)
            .bind("tierCode", tierCode)
            .bind("productId", "io.github.ghkdqhrbals.StudyMate.${tierCode.lowercase()}.monthly")
            .bind("now", now.utc())
            .bind("expiresAt", now.plusSeconds(30L * 24 * 60 * 60).utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun quotaRow(userId: Long): QuotaRow = database.sql(
        """
        select period_started_at, period_ends_at, committed_count, reserved_count,
               remaining_count, version
        from user_quota where user_id = :userId
        """.trimIndent(),
    ).bind("userId", userId).map { row, _ ->
        QuotaRow(
            periodStartedAt = row.get("period_started_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
            periodEndsAt = row.get("period_ends_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
            committedCount = row.get("committed_count", java.lang.Integer::class.java)!!.toInt(),
            reservedCount = row.get("reserved_count", java.lang.Integer::class.java)!!.toInt(),
            remainingCount = row.get("remaining_count", java.lang.Integer::class.java)!!.toInt(),
            version = row.get("version", java.lang.Long::class.java)!!.toLong(),
        )
    }.one().awaitSingle()

    private suspend fun execute(sql: String) {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
    }

    private fun remaining(status: com.buddystudy.backend.study.application.port.outbound.QuestionQuotaStatus): Int =
        (status.monthlyQuestionLimit - status.usedCount - status.reservedCount).coerceAtLeast(0)

    private suspend fun longValue(sql: String): Long = database.sql(sql)
        .map { row -> (row.get(0) as Number).toLong() }.one().awaitSingle()

    private suspend fun stringValue(sql: String): String = database.sql(sql)
        .map { row -> row.get(0, String::class.java)!! }.one().awaitSingle()

    private suspend fun reportConcurrencyFailures(label: String, outcomes: List<Result<Boolean>>) {
        val failures = outcomes.mapNotNull { it.exceptionOrNull() }
        if (failures.isEmpty()) return
        val accepted = outcomes.count { it.getOrNull() == true }
        val rejected = outcomes.count { it.getOrNull() == false }
        System.err.println(
            "quota_concurrency_failure label=$label accepted=$accepted rejected=$rejected " +
                "errors=${failures.size} errorTypes=${failures.groupingBy { it::class.qualifiedName }.eachCount()}",
        )
    }

    private fun Instant.utc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private data class QuotaRow(
        val periodStartedAt: Instant,
        val periodEndsAt: Instant,
        val committedCount: Int,
        val reservedCount: Int,
        val remainingCount: Int,
        val version: Long,
    )
}
