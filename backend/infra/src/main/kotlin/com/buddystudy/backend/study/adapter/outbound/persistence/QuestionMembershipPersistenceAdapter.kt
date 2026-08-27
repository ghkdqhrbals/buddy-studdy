package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.common.application.quota.MonthlyQuestionQuotaPolicy
import com.buddystudy.backend.common.application.quota.MonthlyQuotaWindow
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPlan
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionQuotaStatus
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `user_quota` is the operational source of truth and `user_quota_history` is its append-only audit trail.
 * Every accepted counter mutation and its history row are committed in the same transaction.
 */
@Repository
class QuestionMembershipPersistenceAdapter(
    private val tiers: UserMembershipTierRepository,
    private val template: R2dbcEntityTemplate,
) : QuestionMembershipPort {

    override suspend fun activePlanForUser(userId: Long): QuestionMembershipPlan? =
        activePlanForUser(userId, Instant.now())

    private suspend fun activePlanForUser(userId: Long, at: Instant): QuestionMembershipPlan? {
        val effectivePlan = template.databaseClient.sql(
            """
            select candidates.tier_code, candidates.monthly_question_limit
            from (
                select
                    entitlement.tier_code,
                    tier.monthly_question_limit,
                    case entitlement.tier_code
                        when 'TIER3' then 3
                        when 'TIER2' then 2
                        when 'TIER1' then 1
                        else 0
                    end as tier_rank,
                    entitlement.projected_at as changed_at
                from user_entitlement_projection entitlement
                join user_membership_tiers tier on tier.tier_code = entitlement.tier_code
                where entitlement.user_id = :userId
                  and (
                        entitlement.source = 'FREE'
                        or entitlement.access_status = 'GRACE_PERIOD'
                        or (
                            entitlement.access_status = 'ACTIVE'
                            and (entitlement.expires_at is null or entitlement.expires_at > :at)
                        )
                      )

                union all

                select
                    membership.tier as tier_code,
                    coalesce(membership.monthly_question_limit_override, tier.monthly_question_limit)
                        as monthly_question_limit,
                    case membership.tier
                        when 'TIER3' then 3
                        when 'TIER2' then 2
                        when 'TIER1' then 1
                        else 0
                    end as tier_rank,
                    membership.updated_at as changed_at
                from user_memberships membership
                join user_membership_tiers tier on tier.tier_code = membership.tier
                where membership.user_id = :userId
                  and membership.status = 'ACTIVE'
                  and membership.started_at <= :at
                  and (membership.expires_at is null or membership.expires_at > :at)
            ) candidates
            order by candidates.tier_rank desc,
                     candidates.monthly_question_limit desc,
                     candidates.changed_at desc
            limit 1
            """.trimIndent(),
        ).bind("userId", userId).bind("at", at.utc())
            .map { row, _ ->
                QuestionMembershipPlan(
                    tierCode = row.get("tier_code", String::class.java)!!,
                    monthlyQuestionLimit = (row.get("monthly_question_limit") as Number).toInt(),
                )
            }
            .one().awaitSingleOrNull()
        if (effectivePlan != null) return effectivePlan

        val fallback = tiers.findByTierCode(DEFAULT_TIER) ?: return null
        return QuestionMembershipPlan(fallback.tierCode, fallback.monthlyQuestionLimit)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun quotaStatusForUser(userId: Long, at: Instant): QuestionQuotaStatus? {
        if (lockUserCreatedAt(userId) == null) return null
        val plan = activePlanForUser(userId, at) ?: return null
        val quota = ensureCurrentQuota(userId, plan, at) ?: return null
        return quota.toStatus()
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun tryConsumeMonthlySystemQuestion(
        userId: Long,
        periodStartedAt: Instant,
        limit: Int,
        now: Instant,
    ): Boolean {
        if (lockUserCreatedAt(userId) == null) return false
        val key = "legacy:${UUID.randomUUID()}"
        if (!reserveMonthlySystemQuestion(userId, periodStartedAt, key, key, now)) return false
        commitMonthlySystemQuestion(key, now)
        return true
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun refundMonthlySystemQuestion(userId: Long, periodStartedAt: Instant, now: Instant) {
        if (lockUserCreatedAt(userId) == null) return
        val quota = lockQuota(userId) ?: return
        if (quota.periodStartedAt != periodStartedAt || quota.committedCount == 0) return
        val updated = quota.copy(
            committedCount = quota.committedCount - 1,
            version = quota.version + 1,
            updatedAt = now,
        )
        persistQuota(quota, updated)
        appendHistory(
            eventId = "legacy-release:$userId:${quota.version + 1}",
            userId = userId,
            eventType = "RELEASED",
            affectedPeriodStartedAt = quota.periodStartedAt,
            affectedPeriodEndsAt = quota.periodEndsAt,
            appliedToCurrent = true,
            before = quota,
            after = updated,
            committedDelta = -1,
            reservedDelta = 0,
            bonusDelta = 0,
            reason = "Legacy quota refund",
            occurredAt = now,
        )
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun reserveMonthlySystemQuestion(
        userId: Long,
        periodStartedAt: Instant,
        reservationKey: String,
        correlationId: String,
        now: Instant,
    ): Boolean {
        if (lockUserCreatedAt(userId) == null) return false
        val plan = activePlanForUser(userId, now) ?: return false
        val quota = ensureCurrentQuota(userId, plan, now) ?: return false
        reservationLookup(userId, reservationKey, correlationId).let { existing ->
            if (existing.found) return existing.accepted
        }
        if (quota.committedCount + quota.reservedCount >= quota.baseLimit + quota.bonusLimit) return false

        val inserted = template.databaseClient.sql(
            """
            insert ignore into quota_reservations (
                reservation_key, correlation_id, user_id, quota_period_id,
                period_started_at, period_ends_at, status, reserved_at, created_at, updated_at
            ) values (
                :key, :correlationId, :userId, null,
                :periodStartedAt, :periodEndsAt, 'RESERVED', :now, :now, :now
            )
            """.trimIndent(),
        ).bind("key", reservationKey.take(191))
            .bind("correlationId", correlationId.take(191))
            .bind("userId", userId)
            .bind("periodStartedAt", quota.periodStartedAt.utc())
            .bind("periodEndsAt", quota.periodEndsAt.utc())
            .bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        if (inserted == 0L) {
            return reservationLookup(userId, reservationKey, correlationId).accepted
        }

        val reservationId = reservationId(reservationKey) ?: error("Quota reservation disappeared after insert.")
        val updated = quota.copy(
            reservedCount = quota.reservedCount + 1,
            version = quota.version + 1,
            updatedAt = now,
        )
        persistQuota(quota, updated)
        appendHistory(
            eventId = "reserve:$reservationKey",
            userId = userId,
            reservationId = reservationId,
            eventType = "RESERVED",
            affectedPeriodStartedAt = quota.periodStartedAt,
            affectedPeriodEndsAt = quota.periodEndsAt,
            appliedToCurrent = true,
            before = quota,
            after = updated,
            committedDelta = 0,
            reservedDelta = 1,
            bonusDelta = 0,
            occurredAt = now,
        )
        return true
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun commitMonthlySystemQuestion(reservationKey: String, now: Instant) {
        val ownerUserId = reservationOwner(reservationKey) ?: return
        if (lockUserCreatedAt(ownerUserId) == null) return
        val quota = lockQuota(ownerUserId) ?: return
        val reservation = lockReservation(reservationKey) ?: return
        if (reservation.status != "RESERVED") return
        val appliesToCurrent = quota.matches(reservation)
        val changed = template.databaseClient.sql(
            """
            update quota_reservations
            set status = 'COMMITTED', committed_at = :now, updated_at = :now
            where id = :id and status = 'RESERVED'
            """.trimIndent(),
        ).bind("now", now.utc()).bind("id", reservation.id).fetch().rowsUpdated().awaitSingle()
        if (changed != 1L) return

        val updated = if (appliesToCurrent) {
            quota.copy(
                reservedCount = (quota.reservedCount - 1).coerceAtLeast(0),
                committedCount = quota.committedCount + 1,
                version = quota.version + 1,
                updatedAt = now,
            ).also { persistQuota(quota, it) }
        } else {
            null
        }
        appendHistory(
            eventId = "commit:$reservationKey",
            userId = reservation.userId,
            reservationId = reservation.id,
            eventType = "COMMITTED",
            affectedPeriodStartedAt = reservation.periodStartedAt,
            affectedPeriodEndsAt = reservation.periodEndsAt,
            appliedToCurrent = appliesToCurrent,
            before = quota.takeIf { appliesToCurrent },
            after = updated,
            committedDelta = 1,
            reservedDelta = -1,
            bonusDelta = 0,
            reason = if (appliesToCurrent) null else "Reservation settled after its quota period rolled over",
            occurredAt = now,
        )
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override suspend fun releaseMonthlySystemQuestion(
        userId: Long,
        periodStartedAt: Instant,
        reservationKey: String,
        reason: String?,
        now: Instant,
    ) {
        if (lockUserCreatedAt(userId) == null) return
        val quota = lockQuota(userId) ?: return
        val reservation = lockReservation(reservationKey) ?: return
        if (reservation.userId != userId || reservation.status == "RELEASED") return
        val previousStatus = reservation.status
        val appliesToCurrent = quota.matches(reservation)
        val changed = template.databaseClient.sql(
            """
            update quota_reservations
            set status = 'RELEASED', released_at = :now, release_reason = :reason, updated_at = :now
            where id = :id and status in ('RESERVED', 'COMMITTED')
            """.trimIndent(),
        ).bind("now", now.utc())
            .bind("reason", (reason ?: "Question generation did not complete").take(1000))
            .bind("id", reservation.id)
            .fetch().rowsUpdated().awaitSingle()
        if (changed != 1L) return

        val committedDelta = if (previousStatus == "COMMITTED") -1 else 0
        val reservedDelta = if (previousStatus == "RESERVED") -1 else 0
        val updated = if (appliesToCurrent) {
            quota.copy(
                committedCount = (quota.committedCount + committedDelta).coerceAtLeast(0),
                reservedCount = (quota.reservedCount + reservedDelta).coerceAtLeast(0),
                version = quota.version + 1,
                updatedAt = now,
            ).also { persistQuota(quota, it) }
        } else {
            null
        }
        appendHistory(
            eventId = "release:$reservationKey",
            userId = userId,
            reservationId = reservation.id,
            eventType = "RELEASED",
            affectedPeriodStartedAt = reservation.periodStartedAt,
            affectedPeriodEndsAt = reservation.periodEndsAt,
            appliedToCurrent = appliesToCurrent,
            before = quota.takeIf { appliesToCurrent },
            after = updated,
            committedDelta = committedDelta,
            reservedDelta = reservedDelta,
            bonusDelta = 0,
            reason = reason,
            occurredAt = now,
        )
    }

    private suspend fun ensureCurrentQuota(
        userId: Long,
        plan: QuestionMembershipPlan,
        now: Instant,
    ): UserQuota? {
        ensureQuotaExists(userId, plan, now)
        var quota = lockQuota(userId) ?: return null
        if (!now.isBefore(quota.periodEndsAt) || now.isBefore(quota.periodStartedAt)) {
            quota = rollOver(quota, now)
        }
        // The entitlement may have changed while this request waited for the quota lock. Resolve
        // it again after acquiring the row so an older request can never overwrite a newer paid
        // plan with a stale pre-lock snapshot.
        val lockedPlan = activePlanForUser(userId, now) ?: plan
        if (quota.tierCode != lockedPlan.tierCode || quota.baseLimit != lockedPlan.monthlyQuestionLimit) {
            quota = synchronizePlan(quota, lockedPlan, now)
        }
        return quota
    }

    private suspend fun ensureQuotaExists(userId: Long, plan: QuestionMembershipPlan, now: Instant) {
        if (loadQuota(userId) != null) return
        // A newly registered user has no quota row yet. Locking the stable parent row makes the
        // first materialization single-writer and avoids INSERT IGNORE shared-lock upgrade
        // deadlocks when many question requests arrive for that user at once.
        val createdAt = lockUserCreatedAt(userId) ?: return
        if (loadQuota(userId) != null) return
        val seedPlan = activePlanForUser(userId, now) ?: plan
        val window = MonthlyQuotaWindow.periodAt(createdAt, now)
        val inserted = template.databaseClient.sql(
            """
            insert ignore into user_quota (
                user_id, tier_code, anchor_type, anchor_at, anchor_day, first_paid_at,
                period_started_at, period_ends_at, base_limit, bonus_limit,
                committed_count, reserved_count, policy_version, version, created_at, updated_at
            ) values (
                :userId, :tierCode, 'ACCOUNT_CREATED', :anchorAt, :anchorDay, null,
                :periodStartedAt, :periodEndsAt, :baseLimit, 0,
                0, 0, :policyVersion, 0, :now, :now
            )
            """.trimIndent(),
        ).bind("userId", userId)
            .bind("tierCode", seedPlan.tierCode)
            .bind("anchorAt", createdAt.utc())
            .bind("anchorDay", createdAt.atZone(ZoneOffset.UTC).dayOfMonth)
            .bind("periodStartedAt", window.startedAt.utc())
            .bind("periodEndsAt", window.resetAt.utc())
            .bind("baseLimit", seedPlan.monthlyQuestionLimit)
            .bind("policyVersion", MonthlyQuestionQuotaPolicy.VERSION)
            .bind("now", now.utc())
            .fetch().rowsUpdated().awaitSingle()
        if (inserted == 1L) {
            val created = loadQuota(userId) ?: return
            appendHistory(
                eventId = "quota-created:$userId",
                userId = userId,
                eventType = "QUOTA_CREATED",
                affectedPeriodStartedAt = created.periodStartedAt,
                affectedPeriodEndsAt = created.periodEndsAt,
                appliedToCurrent = true,
                before = null,
                after = created,
                committedDelta = 0,
                reservedDelta = 0,
                bonusDelta = 0,
                reason = "Initial quota projection",
                occurredAt = now,
            )
        }
    }

    private suspend fun rollOver(quota: UserQuota, now: Instant): UserQuota {
        val window = MonthlyQuotaWindow.periodAt(quota.anchorAt, now)
        val updated = quota.copy(
            periodStartedAt = window.startedAt,
            periodEndsAt = window.resetAt,
            bonusLimit = 0,
            committedCount = 0,
            reservedCount = 0,
            policyVersion = MonthlyQuestionQuotaPolicy.VERSION,
            version = quota.version + 1,
            updatedAt = now,
        )
        persistQuota(quota, updated)
        appendHistory(
            eventId = periodResetEventId(quota.userId, window.startedAt),
            userId = quota.userId,
            eventType = "PERIOD_RESET",
            affectedPeriodStartedAt = quota.periodStartedAt,
            affectedPeriodEndsAt = quota.periodEndsAt,
            appliedToCurrent = true,
            before = quota,
            after = updated,
            committedDelta = -quota.committedCount,
            reservedDelta = -quota.reservedCount,
            bonusDelta = -quota.bonusLimit,
            reason = "Monthly quota period rollover to ${window.startedAt}",
            occurredAt = now,
        )
        return updated
    }

    private suspend fun synchronizePlan(
        quota: UserQuota,
        plan: QuestionMembershipPlan,
        now: Instant,
    ): UserQuota {
        val eventType = if (
            MonthlyQuestionQuotaPolicy.isUpgrade(quota.tierCode, plan.tierCode) ||
            (quota.tierCode == plan.tierCode && plan.monthlyQuestionLimit > quota.baseLimit)
        ) {
            "PLAN_UPGRADED"
        } else {
            "PLAN_DOWNGRADED"
        }
        val updated = quota.copy(
            tierCode = plan.tierCode,
            baseLimit = plan.monthlyQuestionLimit,
            policyVersion = MonthlyQuestionQuotaPolicy.VERSION,
            version = quota.version + 1,
            updatedAt = now,
        )
        persistQuota(quota, updated)
        appendHistory(
            eventId = "plan-sync:${quota.userId}:${updated.version}:${plan.tierCode}",
            userId = quota.userId,
            eventType = eventType,
            affectedPeriodStartedAt = quota.periodStartedAt,
            affectedPeriodEndsAt = quota.periodEndsAt,
            appliedToCurrent = true,
            before = quota,
            after = updated,
            committedDelta = 0,
            reservedDelta = 0,
            bonusDelta = 0,
            reason = "Effective quota plan ${quota.tierCode} -> ${plan.tierCode}; usage preserved",
            occurredAt = now,
        )
        return updated
    }

    private suspend fun persistQuota(before: UserQuota, after: UserQuota) {
        val changed = template.databaseClient.sql(
            """
            update user_quota
            set tier_code = :tierCode,
                anchor_type = :anchorType,
                anchor_at = :anchorAt,
                anchor_day = :anchorDay,
                first_paid_at = :firstPaidAt,
                period_started_at = :periodStartedAt,
                period_ends_at = :periodEndsAt,
                base_limit = :baseLimit,
                bonus_limit = :bonusLimit,
                committed_count = :committedCount,
                reserved_count = :reservedCount,
                policy_version = :policyVersion,
                version = :newVersion,
                updated_at = :updatedAt
            where user_id = :userId and version = :expectedVersion
            """.trimIndent(),
        ).bind("tierCode", after.tierCode)
            .bind("anchorType", after.anchorType)
            .bind("anchorAt", after.anchorAt.utc())
            .bind("anchorDay", after.anchorDay)
            .bindOptional("firstPaidAt", after.firstPaidAt?.utc(), LocalDateTime::class.java)
            .bind("periodStartedAt", after.periodStartedAt.utc())
            .bind("periodEndsAt", after.periodEndsAt.utc())
            .bind("baseLimit", after.baseLimit)
            .bind("bonusLimit", after.bonusLimit)
            .bind("committedCount", after.committedCount)
            .bind("reservedCount", after.reservedCount)
            .bind("policyVersion", after.policyVersion)
            .bind("newVersion", after.version)
            .bind("updatedAt", after.updatedAt.utc())
            .bind("userId", before.userId)
            .bind("expectedVersion", before.version)
            .fetch().rowsUpdated().awaitSingle()
        check(changed == 1L) { "Concurrent quota mutation detected for user ${before.userId}." }
    }

    private suspend fun appendHistory(
        eventId: String,
        userId: Long,
        eventType: String,
        affectedPeriodStartedAt: Instant,
        affectedPeriodEndsAt: Instant,
        appliedToCurrent: Boolean,
        before: UserQuota?,
        after: UserQuota?,
        committedDelta: Int,
        reservedDelta: Int,
        bonusDelta: Int,
        occurredAt: Instant,
        reservationId: Long? = null,
        reason: String? = null,
        actorUserId: Long? = null,
    ) {
        template.databaseClient.sql(
            """
            insert into user_quota_history (
                event_id, user_id, reservation_id, event_type,
                affected_period_started_at, affected_period_ends_at, applied_to_current,
                tier_code_before, tier_code_after, base_limit_before, base_limit_after,
                bonus_limit_before, bonus_limit_after,
                committed_count_before, committed_count_after,
                reserved_count_before, reserved_count_after,
                committed_delta, reserved_delta, bonus_delta,
                reason, actor_user_id, quota_version_after, occurred_at, created_at
            ) values (
                :eventId, :userId, :reservationId, :eventType,
                :periodStartedAt, :periodEndsAt, :appliedToCurrent,
                :tierBefore, :tierAfter, :baseBefore, :baseAfter,
                :bonusBefore, :bonusAfter,
                :committedBefore, :committedAfter,
                :reservedBefore, :reservedAfter,
                :committedDelta, :reservedDelta, :bonusDelta,
                :reason, :actorUserId, :quotaVersionAfter, :occurredAt, :createdAt
            )
            """.trimIndent(),
        ).bind("eventId", eventId.take(191))
            .bind("userId", userId)
            .bindOptional("reservationId", reservationId, Long::class.javaObjectType)
            .bind("eventType", eventType)
            .bind("periodStartedAt", affectedPeriodStartedAt.utc())
            .bind("periodEndsAt", affectedPeriodEndsAt.utc())
            .bind("appliedToCurrent", appliedToCurrent)
            .bindOptional("tierBefore", before?.tierCode, String::class.java)
            .bindOptional("tierAfter", after?.tierCode, String::class.java)
            .bindOptional("baseBefore", before?.baseLimit, Int::class.javaObjectType)
            .bindOptional("baseAfter", after?.baseLimit, Int::class.javaObjectType)
            .bindOptional("bonusBefore", before?.bonusLimit, Int::class.javaObjectType)
            .bindOptional("bonusAfter", after?.bonusLimit, Int::class.javaObjectType)
            .bindOptional("committedBefore", before?.committedCount, Int::class.javaObjectType)
            .bindOptional("committedAfter", after?.committedCount, Int::class.javaObjectType)
            .bindOptional("reservedBefore", before?.reservedCount, Int::class.javaObjectType)
            .bindOptional("reservedAfter", after?.reservedCount, Int::class.javaObjectType)
            .bind("committedDelta", committedDelta)
            .bind("reservedDelta", reservedDelta)
            .bind("bonusDelta", bonusDelta)
            .bindOptional("reason", reason?.take(1000), String::class.java)
            .bindOptional("actorUserId", actorUserId, Long::class.javaObjectType)
            .bindOptional("quotaVersionAfter", after?.version, Long::class.javaObjectType)
            .bind("occurredAt", occurredAt.utc())
            .bind("createdAt", occurredAt.utc())
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun lockUserCreatedAt(userId: Long): Instant? = template.databaseClient.sql(
        "select created_at from users where id = :userId for update",
    ).bind("userId", userId)
        .map { row, _ -> row.get("created_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC) }
        .one().awaitSingleOrNull()

    private suspend fun loadQuota(userId: Long): UserQuota? = quotaQuery(userId, false)

    private suspend fun lockQuota(userId: Long): UserQuota? = quotaQuery(userId, true)

    private suspend fun quotaQuery(userId: Long, lock: Boolean): UserQuota? = template.databaseClient.sql(
        """
        select user_id, tier_code, anchor_type, anchor_at, anchor_day, first_paid_at,
               period_started_at, period_ends_at, base_limit, bonus_limit,
               committed_count, reserved_count, policy_version, version, created_at, updated_at
        from user_quota where user_id = :userId${if (lock) " for update" else ""}
        """.trimIndent(),
    ).bind("userId", userId).map { row, _ -> row.userQuota() }.one().awaitSingleOrNull()

    private suspend fun reservationLookup(
        userId: Long,
        key: String,
        correlationId: String,
    ): ReservationLookup {
        val matches = template.databaseClient.sql(
            "select user_id, reservation_key, correlation_id, status from quota_reservations " +
                "where reservation_key = :key or correlation_id = :correlationId",
        ).bind("key", key.take(191)).bind("correlationId", correlationId.take(191))
            .map { row, _ ->
                ExistingReservation(
                    userId = row.long("user_id"),
                    reservationKey = row.get("reservation_key", String::class.java)!!,
                    correlationId = row.get("correlation_id", String::class.java)!!,
                    status = row.get("status", String::class.java)!!,
                )
            }
            .all().collectList().awaitSingle()
        if (matches.isEmpty()) return ReservationLookup(found = false, accepted = false)
        val match = matches.singleOrNull()
            ?: return ReservationLookup(found = true, accepted = false)
        return ReservationLookup(
            found = true,
            accepted = match.userId == userId &&
                match.reservationKey == key.take(191) &&
                match.correlationId == correlationId.take(191) &&
                match.status != "RELEASED",
        )
    }

    private suspend fun reservationOwner(key: String): Long? = template.databaseClient.sql(
        "select user_id from quota_reservations where reservation_key = :key",
    ).bind("key", key.take(191)).map { row, _ -> row.long("user_id") }.one().awaitSingleOrNull()

    private suspend fun reservationId(key: String): Long? = template.databaseClient.sql(
        "select id from quota_reservations where reservation_key = :key",
    ).bind("key", key.take(191)).map { row, _ -> row.long("id") }.one().awaitSingleOrNull()

    private suspend fun lockReservation(key: String): Reservation? = template.databaseClient.sql(
        """
        select id, user_id, status, period_started_at, period_ends_at
        from quota_reservations where reservation_key = :key for update
        """.trimIndent(),
    ).bind("key", key.take(191)).map { row, _ ->
        Reservation(
            id = row.long("id"),
            userId = row.long("user_id"),
            status = row.get("status", String::class.java)!!,
            periodStartedAt = row.instant("period_started_at"),
            periodEndsAt = row.instant("period_ends_at"),
        )
    }.one().awaitSingleOrNull()

    private data class UserQuota(
        val userId: Long,
        val tierCode: String,
        val anchorType: String,
        val anchorAt: Instant,
        val anchorDay: Int,
        val firstPaidAt: Instant?,
        val periodStartedAt: Instant,
        val periodEndsAt: Instant,
        val baseLimit: Int,
        val bonusLimit: Int,
        val committedCount: Int,
        val reservedCount: Int,
        val policyVersion: Int,
        val version: Long,
        val createdAt: Instant,
        val updatedAt: Instant,
    ) {
        fun toStatus() = QuestionQuotaStatus(
            tierCode = tierCode,
            usedCount = committedCount,
            monthlyQuestionLimit = baseLimit + bonusLimit,
            reservedCount = reservedCount,
            baseLimit = baseLimit,
            bonusLimit = bonusLimit,
            periodStartedAt = periodStartedAt,
            resetAt = periodEndsAt,
            anchorType = anchorType,
            policyVersion = policyVersion,
        )

        fun matches(reservation: Reservation): Boolean =
            periodStartedAt == reservation.periodStartedAt && periodEndsAt == reservation.periodEndsAt
    }

    private data class Reservation(
        val id: Long,
        val userId: Long,
        val status: String,
        val periodStartedAt: Instant,
        val periodEndsAt: Instant,
    )

    private data class ReservationLookup(
        val found: Boolean,
        val accepted: Boolean,
    )

    private data class ExistingReservation(
        val userId: Long,
        val reservationKey: String,
        val correlationId: String,
        val status: String,
    )

    private fun Row.userQuota() = UserQuota(
        userId = long("user_id"),
        tierCode = get("tier_code", String::class.java)!!,
        anchorType = get("anchor_type", String::class.java)!!,
        anchorAt = instant("anchor_at"),
        anchorDay = int("anchor_day"),
        firstPaidAt = get("first_paid_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC),
        periodStartedAt = instant("period_started_at"),
        periodEndsAt = instant("period_ends_at"),
        baseLimit = int("base_limit"),
        bonusLimit = int("bonus_limit"),
        committedCount = int("committed_count"),
        reservedCount = int("reserved_count"),
        policyVersion = int("policy_version"),
        version = long("version"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

    private fun Row.long(name: String) = get(name, java.lang.Long::class.java)!!.toLong()
    private fun Row.int(name: String) = get(name, java.lang.Integer::class.java)!!.toInt()
    private fun Row.instant(name: String) = get(name, LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC)

    private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindOptional(name: String, value: T?, type: Class<T>) =
        if (value == null) bindNull(name, type) else bind(name, value)

    private fun Instant.utc(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun periodResetEventId(userId: Long, periodStartedAt: Instant): String =
        "quota-period-reset:$userId:${periodStartedAt.toEpochMilli()}".take(191)

    private companion object {
        const val DEFAULT_TIER = "TIER1"
    }
}
