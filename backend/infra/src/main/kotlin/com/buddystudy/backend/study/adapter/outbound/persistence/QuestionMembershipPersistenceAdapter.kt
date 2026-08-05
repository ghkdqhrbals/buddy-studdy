package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.account.domain.entity.MembershipStatus
import com.buddystudy.backend.common.application.quota.MonthlyQuotaWindow
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPlan
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionQuotaStatus
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class QuestionMembershipPersistenceAdapter(
    private val memberships: UserMembershipRepository,
    private val tiers: UserMembershipTierRepository,
    private val template: R2dbcEntityTemplate,
) : QuestionMembershipPort {

    override suspend fun activePlanForUser(userId: Long): QuestionMembershipPlan? {
        val projectedTier = template.databaseClient.sql(
            "select tier_code from user_entitlement_projection where user_id = :userId",
        ).bind("userId", userId).map { row, _ -> row.get("tier_code", String::class.java)!! }
            .one().awaitSingleOrNull()
        if (projectedTier != null) {
            val tier = tiers.findByTierCode(projectedTier) ?: return null
            return QuestionMembershipPlan(tier.tierCode, tier.monthlyQuestionLimit)
        }

        val membership = memberships.findFirstByUserIdAndStatusOrderByUpdatedAtDesc(userId, MembershipStatus.ACTIVE)
        val now = Instant.now()
        val activeMembership = membership?.takeIf {
            !it.startedAt.isAfter(now) && it.expiresAt?.isAfter(now) != false
        }
        val tierCode = activeMembership?.tier ?: DEFAULT_TIER
        val tier = tiers.findByTierCode(tierCode) ?: tiers.findByTierCode(DEFAULT_TIER) ?: return null
        return QuestionMembershipPlan(
            tierCode = tier.tierCode,
            monthlyQuestionLimit = activeMembership?.monthlyQuestionLimitOverride ?: tier.monthlyQuestionLimit,
        )
    }

    override suspend fun quotaStatusForUser(userId: Long, at: Instant): QuestionQuotaStatus? {
        val plan = activePlanForUser(userId) ?: return null
        // Status reads must stay side-effect free. A quota account/period is materialized only by
        // the first reservation or bonus grant; until then the user's creation instant is the
        // effective free-tier anchor and counters are zero.
        val account = resolveQuotaAccount(userId) ?: return null
        val window = MonthlyQuotaWindow.periodAt(account.anchorAt, at)
        val period = loadPeriod(userId, window.startedAt)
        val committed = period?.committedCount ?: 0
        val reserved = period?.reservedCount ?: 0
        val bonus = period?.bonusCount ?: 0
        return QuestionQuotaStatus(
            tierCode = plan.tierCode,
            usedCount = committed,
            monthlyQuestionLimit = plan.monthlyQuestionLimit + bonus,
            reservedCount = reserved,
            baseLimit = plan.monthlyQuestionLimit,
            bonusLimit = bonus,
            periodStartedAt = window.startedAt,
            resetAt = window.resetAt,
            anchorType = account.anchorType,
            policyVersion = account.policyVersion,
        )
    }

    /** Compatibility path for callers not yet carrying a correlation ID. New generation uses reserve/commit. */
    @Transactional
    override suspend fun tryConsumeMonthlySystemQuestion(
        userId: Long,
        periodStartedAt: Instant,
        limit: Int,
        now: Instant,
    ): Boolean {
        val key = "legacy:${UUID.randomUUID()}"
        if (!reserveMonthlySystemQuestion(userId, periodStartedAt, key, key, now)) return false
        commitMonthlySystemQuestion(key, now)
        return true
    }

    @Transactional
    override suspend fun refundMonthlySystemQuestion(userId: Long, periodStartedAt: Instant, now: Instant) {
        // Legacy counters have no reservation identity. This method remains dual-write compatible only.
        template.databaseClient.sql(
            """
            update user_monthly_question_usage
            set system_question_count = greatest(system_question_count - 1, 0), updated_at = :now
            where user_id = :userId and period_start = :periodStartedAt and system_question_count > 0
            """.trimIndent(),
        ).bind("userId", userId).bind("periodStartedAt", periodStartedAt.utc()).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    @Transactional
    override suspend fun reserveMonthlySystemQuestion(
        userId: Long,
        periodStartedAt: Instant,
        reservationKey: String,
        correlationId: String,
        now: Instant,
    ): Boolean {
        val existing = reservationStatus(reservationKey)
        if (existing != null) return existing != "RELEASED"

        val account = ensureQuotaAccount(userId, now) ?: return false
        val window = MonthlyQuotaWindow.periodAt(account.anchorAt, now)
        ensurePeriod(userId, window.startedAt, window.resetAt, now)
        val period = loadPeriod(userId, window.startedAt) ?: return false
        val plan = activePlanForUser(userId) ?: return false

        // Let MySQL serialize the counter update instead of taking a separate SELECT ... FOR UPDATE
        // lock. This avoids lock-upgrade deadlocks when many first-use requests create reservations
        // for the same period concurrently, while the predicate still prevents over-allocation.
        val reserved = template.databaseClient.sql(
            """
            update quota_periods
            set reserved_count = reserved_count + 1, updated_at = :now
            where id = :id
              and committed_count + reserved_count < :baseLimit + bonus_count
            """.trimIndent(),
        ).bind("now", now.utc()).bind("id", period.id).bind("baseLimit", plan.monthlyQuestionLimit)
            .fetch().rowsUpdated().awaitSingle()
        if (reserved != 1L) return false

        val inserted = template.databaseClient.sql(
            """
            insert ignore into quota_reservations (
                reservation_key, correlation_id, user_id, quota_period_id, status,
                reserved_at, created_at, updated_at
            ) values (:key, :correlationId, :userId, :periodId, 'RESERVED', :now, :now, :now)
            """.trimIndent(),
        ).bind("key", reservationKey.take(191)).bind("correlationId", correlationId.take(191))
            .bind("userId", userId).bind("periodId", period.id).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        if (inserted == 0L) {
            template.databaseClient.sql(
                "update quota_periods set reserved_count = greatest(reserved_count - 1, 0), updated_at = :now where id = :id",
            ).bind("now", now.utc()).bind("id", period.id).fetch().rowsUpdated().awaitSingle()
            return reservationStatus(reservationKey) != "RELEASED"
        }

        val reservationId = reservationId(reservationKey) ?: error("Quota reservation disappeared after insert.")
        appendLedger(
            eventId = "reserve:$reservationKey",
            userId = userId,
            periodId = period.id,
            reservationId = reservationId,
            type = "RESERVE",
            committedDelta = 0,
            reservedDelta = 1,
            bonusDelta = 0,
            reason = null,
            now = now,
        )
        return true
    }

    @Transactional
    override suspend fun commitMonthlySystemQuestion(reservationKey: String, now: Instant) {
        val reservation = lockReservation(reservationKey) ?: return
        if (reservation.status != "RESERVED") return
        val changed = template.databaseClient.sql(
            """
            update quota_reservations
            set status = 'COMMITTED', committed_at = :now, updated_at = :now
            where id = :id and status = 'RESERVED'
            """.trimIndent(),
        ).bind("now", now.utc()).bind("id", reservation.id).fetch().rowsUpdated().awaitSingle()
        if (changed != 1L) return
        template.databaseClient.sql(
            """
            update quota_periods
            set reserved_count = greatest(reserved_count - 1, 0), committed_count = committed_count + 1,
                updated_at = :now
            where id = :id
            """.trimIndent(),
        ).bind("now", now.utc()).bind("id", reservation.periodId).fetch().rowsUpdated().awaitSingle()
        appendLedger(
            "commit:$reservationKey", reservation.userId, reservation.periodId, reservation.id,
            "COMMIT", 1, -1, 0, null, now,
        )
        dualWriteCommitted(reservation.userId, reservation.periodStartedAt, now)
    }

    @Transactional
    override suspend fun releaseMonthlySystemQuestion(
        userId: Long,
        periodStartedAt: Instant,
        reservationKey: String,
        reason: String?,
        now: Instant,
    ) {
        val reservation = lockReservation(reservationKey) ?: return
        if (reservation.userId != userId || reservation.status == "RELEASED") return
        val previousStatus = reservation.status
        val changed = template.databaseClient.sql(
            """
            update quota_reservations
            set status = 'RELEASED', released_at = :now, release_reason = :reason, updated_at = :now
            where id = :id and status in ('RESERVED', 'COMMITTED')
            """.trimIndent(),
        ).bind("now", now.utc()).bind("reason", (reason ?: "Question generation did not complete").take(1000))
            .bind("id", reservation.id).fetch().rowsUpdated().awaitSingle()
        if (changed != 1L) return
        val counterSql = if (previousStatus == "COMMITTED") {
            "update quota_periods set committed_count = greatest(committed_count - 1, 0), updated_at = :now where id = :id"
        } else {
            "update quota_periods set reserved_count = greatest(reserved_count - 1, 0), updated_at = :now where id = :id"
        }
        template.databaseClient.sql(counterSql).bind("now", now.utc()).bind("id", reservation.periodId)
            .fetch().rowsUpdated().awaitSingle()
        appendLedger(
            "release:$reservationKey", userId, reservation.periodId, reservation.id,
            "RELEASE", if (previousStatus == "COMMITTED") -1 else 0,
            if (previousStatus == "RESERVED") -1 else 0, 0, reason, now,
        )
        if (previousStatus == "COMMITTED") refundLegacyCommitted(userId, reservation.periodStartedAt, now)
    }

    private suspend fun ensureQuotaAccount(userId: Long, now: Instant): QuotaAccount? {
        loadQuotaAccount(userId)?.let { return it }
        val createdAt = loadUserCreatedAt(userId) ?: return null
        template.databaseClient.sql(
            """
            insert ignore into quota_accounts (
                user_id, anchor_type, anchor_at, anchor_day, first_paid_at, policy_version, created_at, updated_at
            ) values (:userId, 'ACCOUNT_CREATED', :anchorAt, :anchorDay, null, 2, :now, :now)
            """.trimIndent(),
        ).bind("userId", userId).bind("anchorAt", createdAt.utc())
            .bind("anchorDay", createdAt.atZone(ZoneOffset.UTC).dayOfMonth).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        return loadQuotaAccount(userId)
    }

    private suspend fun resolveQuotaAccount(userId: Long): QuotaAccount? =
        loadQuotaAccount(userId) ?: loadUserCreatedAt(userId)?.let { createdAt ->
            QuotaAccount(
                anchorType = "ACCOUNT_CREATED",
                anchorAt = createdAt,
                policyVersion = 2,
            )
        }

    private suspend fun loadUserCreatedAt(userId: Long): Instant? = template.databaseClient.sql(
        "select created_at from users where id = :userId",
    ).bind("userId", userId)
        .map { row, _ -> row.get("created_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC) }
        .one().awaitSingleOrNull()

    private suspend fun loadQuotaAccount(userId: Long): QuotaAccount? = template.databaseClient.sql(
        "select anchor_type, anchor_at, policy_version from quota_accounts where user_id = :userId",
    ).bind("userId", userId).map { row, _ ->
        QuotaAccount(
            anchorType = row.get("anchor_type", String::class.java)!!,
            anchorAt = row.get("anchor_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
            policyVersion = row.get("policy_version", java.lang.Integer::class.java)!!.toInt(),
        )
    }.one().awaitSingleOrNull()

    private suspend fun ensurePeriod(userId: Long, start: Instant, end: Instant, now: Instant) {
        template.databaseClient.sql(
            """
            insert ignore into quota_periods (
                user_id, period_started_at, period_ends_at, committed_count, reserved_count, bonus_count,
                policy_version, created_at, updated_at
            ) values (:userId, :startedAt, :endsAt, 0, 0, 0, 2, :now, :now)
            """.trimIndent(),
        ).bind("userId", userId).bind("startedAt", start.utc()).bind("endsAt", end.utc()).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun loadPeriod(userId: Long, start: Instant): QuotaPeriod? = template.databaseClient.sql(
        """
        select id, committed_count, reserved_count, bonus_count, period_started_at
        from quota_periods where user_id = :userId and period_started_at = :startedAt
        """.trimIndent(),
    ).bind("userId", userId).bind("startedAt", start.utc()).map { row, _ -> row.quotaPeriod() }
        .one().awaitSingleOrNull()

    private suspend fun reservationStatus(key: String): String? = template.databaseClient.sql(
        "select status from quota_reservations where reservation_key = :key",
    ).bind("key", key.take(191)).map { row, _ -> row.get("status", String::class.java)!! }
        .one().awaitSingleOrNull()

    private suspend fun reservationId(key: String): Long? = template.databaseClient.sql(
        "select id from quota_reservations where reservation_key = :key",
    ).bind("key", key.take(191)).map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
        .one().awaitSingleOrNull()

    private suspend fun lockReservation(key: String): Reservation? = template.databaseClient.sql(
        """
        select r.id, r.user_id, r.quota_period_id, r.status, p.period_started_at
        from quota_reservations r join quota_periods p on p.id = r.quota_period_id
        where r.reservation_key = :key for update
        """.trimIndent(),
    ).bind("key", key.take(191)).map { row, _ ->
        Reservation(
            id = row.get("id", java.lang.Long::class.java)!!.toLong(),
            userId = row.get("user_id", java.lang.Long::class.java)!!.toLong(),
            periodId = row.get("quota_period_id", java.lang.Long::class.java)!!.toLong(),
            status = row.get("status", String::class.java)!!,
            periodStartedAt = row.get("period_started_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
        )
    }.one().awaitSingleOrNull()

    private suspend fun appendLedger(
        eventId: String,
        userId: Long,
        periodId: Long,
        reservationId: Long?,
        type: String,
        committedDelta: Int,
        reservedDelta: Int,
        bonusDelta: Int,
        reason: String?,
        now: Instant,
    ) {
        var spec = template.databaseClient.sql(
            """
            insert ignore into quota_ledger (
                ledger_event_id, user_id, quota_period_id, reservation_id, ledger_type,
                committed_delta, reserved_delta, bonus_delta, reason, occurred_at, created_at
            ) values (:eventId, :userId, :periodId, :reservationId, :type,
                      :committedDelta, :reservedDelta, :bonusDelta, :reason, :now, :now)
            """.trimIndent(),
        ).bind("eventId", eventId.take(191)).bind("userId", userId).bind("periodId", periodId)
            .bind("type", type).bind("committedDelta", committedDelta).bind("reservedDelta", reservedDelta)
            .bind("bonusDelta", bonusDelta).bind("now", now.utc())
        spec = if (reservationId == null) spec.bindNull("reservationId", java.lang.Long::class.java)
        else spec.bind("reservationId", reservationId)
        spec = if (reason == null) spec.bindNull("reason", String::class.java) else spec.bind("reason", reason.take(1000))
        spec.fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun dualWriteCommitted(userId: Long, periodStartedAt: Instant, now: Instant) {
        template.databaseClient.sql(
            """
            insert into user_monthly_question_usage
                (user_id, usage_month, period_start, system_question_count, created_at, updated_at)
            values (:userId, :usageMonth, :periodStartedAt, 1, :now, :now)
            on duplicate key update system_question_count = system_question_count + 1, updated_at = :now
            """.trimIndent(),
        ).bind("userId", userId).bind("usageMonth", periodStartedAt.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).toString().take(7))
            .bind("periodStartedAt", periodStartedAt.utc()).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun refundLegacyCommitted(userId: Long, periodStartedAt: Instant, now: Instant) {
        template.databaseClient.sql(
            """
            update user_monthly_question_usage
            set system_question_count = greatest(system_question_count - 1, 0), updated_at = :now
            where user_id = :userId and period_start = :periodStartedAt and system_question_count > 0
            """.trimIndent(),
        ).bind("userId", userId).bind("periodStartedAt", periodStartedAt.utc()).bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    private companion object {
        const val DEFAULT_TIER = "TIER1"
    }

    private data class QuotaAccount(val anchorType: String, val anchorAt: Instant, val policyVersion: Int)
    private data class QuotaPeriod(
        val id: Long,
        val committedCount: Int,
        val reservedCount: Int,
        val bonusCount: Int,
        val periodStartedAt: Instant,
    )
    private data class Reservation(
        val id: Long,
        val userId: Long,
        val periodId: Long,
        val status: String,
        val periodStartedAt: Instant,
    )

    private fun io.r2dbc.spi.Row.quotaPeriod() = QuotaPeriod(
        id = get("id", java.lang.Long::class.java)!!.toLong(),
        committedCount = get("committed_count", java.lang.Integer::class.java)!!.toInt(),
        reservedCount = get("reserved_count", java.lang.Integer::class.java)!!.toInt(),
        bonusCount = get("bonus_count", java.lang.Integer::class.java)!!.toInt(),
        periodStartedAt = get("period_started_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
    )

    private fun Instant.utc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
