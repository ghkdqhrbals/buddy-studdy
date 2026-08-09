package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.common.application.quota.MonthlyQuestionQuotaPolicy
import com.buddystudy.backend.common.application.quota.MonthlyQuotaWindow
import com.buddystudy.backend.study.application.port.outbound.QuestionQuotaRolloverPort
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class QuestionQuotaRolloverPersistenceAdapter(
    private val database: DatabaseClient,
) : QuestionQuotaRolloverPort {
    @Transactional
    override suspend fun rolloverDue(at: Instant, batchSize: Int): Int {
        val candidateUserIds = database.sql(
            """
            select user_id
            from user_quota
            where period_ends_at <= :at
            order by period_ends_at, user_id
            limit :batchSize
            """.trimIndent(),
        ).bind("at", at.utc()).bind("batchSize", batchSize)
            .map { row, _ -> row.get("user_id", java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()

        var rolledOver = 0
        candidateUserIds.forEach { userId ->
            if (!tryLockUser(userId, skipLocked = true)) return@forEach
            val quota = lockDueQuota(userId, at) ?: return@forEach
            rolloverLocked(quota, at)
            rolledOver += 1
        }
        return rolledOver
    }

    @Transactional
    override suspend fun rolloverUserIfDue(userId: Long, at: Instant): Boolean {
        if (!tryLockUser(userId, skipLocked = false)) return false
        val due = lockDueQuota(userId, at) ?: return false

        rolloverLocked(due, at)
        return true
    }

    private suspend fun lockDueQuota(userId: Long, at: Instant): LockedQuota? = database.sql(
            """
            select user_id, tier_code, anchor_at, period_started_at, period_ends_at,
                   base_limit, bonus_limit, committed_count, reserved_count, version
            from user_quota
            where user_id = :userId and period_ends_at <= :at
            for update
            """.trimIndent(),
        ).bind("userId", userId).bind("at", at.utc())
            .map { row, _ -> row.toLockedQuota() }
            .one().awaitSingleOrNull()

    private suspend fun tryLockUser(userId: Long, skipLocked: Boolean): Boolean = database.sql(
        "select id from users where id = :userId for update${if (skipLocked) " skip locked" else ""}",
    ).bind("userId", userId)
        .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
        .one().awaitSingleOrNull() != null

    private suspend fun rolloverLocked(quota: LockedQuota, at: Instant) {
        val next = MonthlyQuotaWindow.periodAt(quota.anchorAt, at)
        check(next.startedAt.isAfter(quota.periodStartedAt)) {
            "Expired quota row did not advance to a later period for user ${quota.userId}."
        }
        val versionAfter = quota.version + 1
        val changed = database.sql(
            """
            update user_quota
            set period_started_at = :periodStartedAt,
                period_ends_at = :periodEndsAt,
                bonus_limit = 0,
                committed_count = 0,
                reserved_count = 0,
                policy_version = :policyVersion,
                version = :versionAfter,
                updated_at = :at
            where user_id = :userId and version = :versionBefore and period_ends_at <= :at
            """.trimIndent(),
        ).bind("periodStartedAt", next.startedAt.utc())
            .bind("periodEndsAt", next.resetAt.utc())
            .bind("policyVersion", MonthlyQuestionQuotaPolicy.VERSION)
            .bind("versionAfter", versionAfter)
            .bind("at", at.utc())
            .bind("userId", quota.userId)
            .bind("versionBefore", quota.version)
            .fetch().rowsUpdated().awaitSingle()
        check(changed == 1L) { "Quota rollover lost its row lock for user ${quota.userId}." }

        database.sql(
            """
            insert into user_quota_history (
                event_id, user_id, event_type,
                affected_period_started_at, affected_period_ends_at, applied_to_current,
                committed_delta, reserved_delta, bonus_delta,
                tier_code_before, tier_code_after,
                base_limit_before, base_limit_after,
                bonus_limit_before, bonus_limit_after,
                committed_count_before, committed_count_after,
                reserved_count_before, reserved_count_after,
                actor_user_id, reason, quota_version_after, occurred_at, created_at
            ) values (
                :eventId, :userId, 'PERIOD_RESET',
                :affectedPeriodStartedAt, :affectedPeriodEndsAt, true,
                :committedDelta, :reservedDelta, :bonusDelta,
                :tierCode, :tierCode,
                :baseLimit, :baseLimit,
                :bonusBefore, 0,
                :committedBefore, 0,
                :reservedBefore, 0,
                null, :reason, :versionAfter, :at, :at
            )
            """.trimIndent(),
        ).bind("eventId", periodResetEventId(quota.userId, next.startedAt))
            .bind("userId", quota.userId)
            .bind("affectedPeriodStartedAt", quota.periodStartedAt.utc())
            .bind("affectedPeriodEndsAt", quota.periodEndsAt.utc())
            .bind("committedDelta", -quota.committedCount)
            .bind("reservedDelta", -quota.reservedCount)
            .bind("bonusDelta", -quota.bonusLimit)
            .bind("tierCode", quota.tierCode)
            .bind("baseLimit", quota.baseLimit)
            .bind("bonusBefore", quota.bonusLimit)
            .bind("committedBefore", quota.committedCount)
            .bind("reservedBefore", quota.reservedCount)
            .bind(
                "reason",
                "Monthly quota period advanced from ${quota.periodStartedAt} to ${next.startedAt}".take(1000),
            )
            .bind("versionAfter", versionAfter)
            .bind("at", at.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    private fun periodResetEventId(userId: Long, periodStartedAt: Instant): String =
        "quota-period-reset:$userId:${periodStartedAt.toEpochMilli()}".take(191)

    private data class LockedQuota(
        val userId: Long,
        val tierCode: String,
        val anchorAt: Instant,
        val periodStartedAt: Instant,
        val periodEndsAt: Instant,
        val baseLimit: Int,
        val bonusLimit: Int,
        val committedCount: Int,
        val reservedCount: Int,
        val version: Long,
    )

    private fun Row.toLockedQuota() = LockedQuota(
        userId = get("user_id", java.lang.Long::class.java)!!.toLong(),
        tierCode = get("tier_code", String::class.java)!!,
        anchorAt = instant("anchor_at"),
        periodStartedAt = instant("period_started_at"),
        periodEndsAt = instant("period_ends_at"),
        baseLimit = get("base_limit", java.lang.Integer::class.java)!!.toInt(),
        bonusLimit = get("bonus_limit", java.lang.Integer::class.java)!!.toInt(),
        committedCount = get("committed_count", java.lang.Integer::class.java)!!.toInt(),
        reservedCount = get("reserved_count", java.lang.Integer::class.java)!!.toInt(),
        version = get("version", java.lang.Long::class.java)!!.toLong(),
    )

    private fun Row.instant(name: String): Instant =
        get(name, LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC)

    private fun Instant.utc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
