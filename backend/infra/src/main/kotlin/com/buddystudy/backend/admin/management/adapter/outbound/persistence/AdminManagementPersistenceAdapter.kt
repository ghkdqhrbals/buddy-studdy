package com.buddystudy.backend.admin.management.adapter.outbound.persistence

import com.buddystudy.backend.admin.management.application.model.AdminMembershipTierResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserSummary
import com.buddystudy.backend.admin.management.application.model.AssignUserPlanCommand
import com.buddystudy.backend.admin.management.application.port.outbound.AdminManagementPort
import com.buddystudy.backend.common.application.quota.MonthlyQuestionQuotaPolicy
import com.buddystudy.backend.common.application.quota.MonthlyQuotaWindow
import com.buddystudy.backend.study.application.port.inbound.QuestionQuotaRolloverUseCase
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
class AdminManagementPersistenceAdapter(
    private val database: DatabaseClient,
    private val quotaRollover: QuestionQuotaRolloverUseCase,
) : AdminManagementPort {
    override suspend fun users(query: String?, limit: Int, offset: Int): AdminUserPageResponse {
        val now = Instant.now()
        val search = query?.lowercase()?.let { "%$it%" }
        val where = buildString {
            append("where u.status <> 'ANONYMOUS'")
            if (search != null) {
                append(" and (lower(u.email) like :query or lower(u.display_name) like :query or cast(u.id as char) = :exactQuery)")
            }
        }
        val countSpec = database.sql("select count(*) as total_count from users u $where")
            .bindSearch(search, query)
        val totalCount = countSpec.map { row, _ -> row.long("total_count") }.one().awaitSingle()
        val listSpec = database.sql(
            """
            ${userSelect()}
            $where
            order by u.id desc
            limit :limit offset :offset
            """.trimIndent(),
        ).bindSearch(search, query)
            .bind("limit", limit)
            .bind("offset", offset)
        val userRows = listSpec.map { row, _ -> row.toAdminUserRow() }.all().collectList().awaitSingle()
        val usageByUser = currentUsageByUser(userRows, now)
        val users = userRows.map { it.toAdminUser(usageByUser[it.id] ?: CurrentPeriodUsage(), now) }
        return AdminUserPageResponse(users, totalCount, limit, offset)
    }

    override suspend fun user(userId: Long): AdminUserSummary? {
        val now = Instant.now()
        val row = database.sql(
            """
            ${userSelect()}
            where u.id = :userId
              and u.status <> 'ANONYMOUS'
            """.trimIndent(),
        ).bind("userId", userId)
            .map { result, _ -> result.toAdminUserRow() }
            .one()
            .awaitSingleOrNull()
            ?: return null
        val usage = currentUsageByUser(listOf(row), now)[userId] ?: CurrentPeriodUsage()
        return row.toAdminUser(usage, now)
    }

    override suspend fun tiers(): List<AdminMembershipTierResponse> =
        database.sql(
            """
            select tier_code, monthly_question_limit, description
            from user_membership_tiers
            order by monthly_question_limit, tier_code
            """.trimIndent(),
        ).map { row, _ ->
            AdminMembershipTierResponse(
                tierCode = row.string("tier_code"),
                monthlyQuestionLimit = row.int("monthly_question_limit"),
                description = row.string("description"),
            )
        }.all().collectList().awaitSingle()

    @Transactional
    override suspend fun updateTier(tierCode: String, monthlyQuestionLimit: Int): AdminMembershipTierResponse? {
        val now = Instant.now()
        val previousLimit = database.sql(
            "select monthly_question_limit from user_membership_tiers where tier_code = :tierCode for update",
        ).bind("tierCode", tierCode).map { row, _ -> row.int("monthly_question_limit") }
            .one().awaitSingleOrNull() ?: return null
        if (previousLimit == monthlyQuestionLimit) {
            return tiers().firstOrNull { it.tierCode == tierCode }
        }
        val changed = database.sql(
            """
            update user_membership_tiers
            set monthly_question_limit = :limit, updated_at = :now
            where tier_code = :tierCode
            """.trimIndent(),
        ).bind("limit", monthlyQuestionLimit)
            .bind("now", now)
            .bind("tierCode", tierCode)
            .fetch().rowsUpdated().awaitSingle()
        if (changed == 0L) return null
        val affectedUserIds = database.sql(
            """
            select user_id
            from user_quota
            where tier_code = :tierCode and base_limit = :previousLimit
            order by user_id
            """.trimIndent(),
        ).bind("tierCode", tierCode).bind("previousLimit", previousLimit)
            .map { row, _ -> row.long("user_id") }
            .all().collectList().awaitSingle()
        affectedUserIds.forEach { userId ->
            database.sql("select id from users where id = :userId for update")
                .bind("userId", userId).map { row, _ -> row.long("id") }
                .one().awaitSingleOrNull() ?: return@forEach
            val quota = database.sql(
                """
                select tier_code, period_started_at, period_ends_at,
                       base_limit, bonus_limit, committed_count, reserved_count, version
                from user_quota
                where user_id = :userId and tier_code = :tierCode and base_limit = :previousLimit
                for update
                """.trimIndent(),
            ).bind("userId", userId).bind("tierCode", tierCode).bind("previousLimit", previousLimit)
                .map { row, _ -> AdminQuotaMutationRow(
                    tierCode = row.string("tier_code"),
                    periodStartedAt = row.instant("period_started_at"),
                    periodEndsAt = row.instant("period_ends_at"),
                    baseLimit = row.int("base_limit"),
                    bonusLimit = row.int("bonus_limit"),
                    committedCount = row.int("committed_count"),
                    reservedCount = row.int("reserved_count"),
                    version = row.long("version"),
                ) }.one().awaitSingleOrNull() ?: return@forEach
            val nextVersion = quota.version + 1
            database.sql(
                """
                update user_quota
                set base_limit = :newLimit, policy_version = :policyVersion,
                    version = :nextVersion, updated_at = :now
                where user_id = :userId and version = :version
                """.trimIndent(),
            ).bind("newLimit", monthlyQuestionLimit)
                .bind("policyVersion", MonthlyQuestionQuotaPolicy.VERSION)
                .bind("nextVersion", nextVersion).bind("now", now)
                .bind("userId", userId).bind("version", quota.version)
                .fetch().rowsUpdated().awaitSingle()
            database.sql(
                """
                insert into user_quota_history (
                    event_id, user_id, event_type,
                    affected_period_started_at, affected_period_ends_at, applied_to_current,
                    tier_code_before, tier_code_after, base_limit_before, base_limit_after,
                    bonus_limit_before, bonus_limit_after,
                    committed_count_before, committed_count_after,
                    reserved_count_before, reserved_count_after,
                    committed_delta, reserved_delta, bonus_delta,
                    reason, quota_version_after, occurred_at, created_at
                ) values (
                    :eventId, :userId, :eventType,
                    :periodStartedAt, :periodEndsAt, true,
                    :tierCode, :tierCode, :baseBefore, :baseAfter,
                    :bonus, :bonus, :committed, :committed, :reserved, :reserved,
                    0, 0, 0, 'Administrator changed the tier default limit; usage preserved',
                    :nextVersion, :now, :now
                )
                """.trimIndent(),
            ).bind("eventId", "tier-default-change:$tierCode:$userId:$nextVersion")
                .bind("userId", userId)
                .bind("eventType", if (monthlyQuestionLimit > quota.baseLimit) "PLAN_UPGRADED" else "PLAN_DOWNGRADED")
                .bind("periodStartedAt", quota.periodStartedAt).bind("periodEndsAt", quota.periodEndsAt)
                .bind("tierCode", quota.tierCode).bind("baseBefore", quota.baseLimit)
                .bind("baseAfter", monthlyQuestionLimit).bind("bonus", quota.bonusLimit)
                .bind("committed", quota.committedCount).bind("reserved", quota.reservedCount)
                .bind("nextVersion", nextVersion).bind("now", now)
                .fetch().rowsUpdated().awaitSingle()
        }
        return tiers().firstOrNull { it.tierCode == tierCode }
    }

    @Transactional
    override suspend fun assignPlan(userId: Long, command: AssignUserPlanCommand): AdminUserSummary? {
        val tierExists = database.sql("select count(*) as count_value from user_membership_tiers where tier_code = :tierCode")
            .bind("tierCode", command.tierCode)
            .map { row, _ -> row.long("count_value") > 0 }
            .one().awaitSingle()
        val userExists = database.sql("select count(*) as count_value from users where id = :userId and status <> 'ANONYMOUS'")
            .bind("userId", userId)
            .map { row, _ -> row.long("count_value") > 0 }
            .one().awaitSingle()
        if (!tierExists || !userExists) return null

        val now = Instant.now()
        database.sql(
            """
            update user_memberships
            set status = 'INACTIVE', updated_at = :now
            where user_id = :userId and status = 'ACTIVE'
            """.trimIndent(),
        ).bind("now", now).bind("userId", userId).fetch().rowsUpdated().awaitSingle()

        var insert = database.sql(
            """
            insert into user_memberships
                (user_id, tier, monthly_question_limit_override, status, started_at, expires_at, created_at, updated_at)
            values
                (:userId, :tierCode, :overrideLimit, 'ACTIVE', :now, null, :now, :now)
            """.trimIndent(),
        ).bind("userId", userId)
            .bind("tierCode", command.tierCode)
            .bind("now", now)
        val overrideLimit = command.monthlyQuestionLimitOverride
        insert = if (overrideLimit == null) {
            insert.bindNull("overrideLimit", Integer::class.java)
        } else {
            insert.bind("overrideLimit", overrideLimit)
        }
        insert.fetch().rowsUpdated().awaitSingle()
        val tierLimit = overrideLimit ?: database.sql(
            "select monthly_question_limit from user_membership_tiers where tier_code = :tierCode",
        ).bind("tierCode", command.tierCode).map { row, _ -> row.int("monthly_question_limit") }
            .one().awaitSingle()
        synchronizeAdminPlan(userId, command.tierCode, tierLimit, now)
        return user(userId)
    }

    @Transactional
    override suspend fun setCurrentPeriodQuestionLimit(
        userId: Long,
        questionLimitOverride: Int?,
    ): AdminUserSummary? {
        val now = Instant.now()
        if (!ensureAdminQuotaExists(userId, now = now)) return null
        quotaRollover.rolloverUserIfDue(userId, now)
        val quota = database.sql(
            """
            select q.tier_code, q.period_started_at, q.period_ends_at,
                   q.base_limit, q.bonus_limit, q.committed_count, q.reserved_count,
                   q.version
            from user_quota q
            where q.user_id = :userId for update
            """.trimIndent(),
        ).bind("userId", userId).map { row, _ ->
            AdminQuotaMutationRow(
                tierCode = row.string("tier_code"),
                periodStartedAt = row.instant("period_started_at"),
                periodEndsAt = row.instant("period_ends_at"),
                baseLimit = row.int("base_limit"),
                bonusLimit = row.int("bonus_limit"),
                committedCount = row.int("committed_count"),
                reservedCount = row.int("reserved_count"),
                version = row.long("version"),
            )
        }.one().awaitSingleOrNull() ?: return null
        val nextBonusLimit = questionLimitOverride
            ?.let { requestedTotal -> (requestedTotal - quota.baseLimit).coerceAtLeast(0) }
            ?: 0
        if (nextBonusLimit != quota.bonusLimit) {
            val nextVersion = quota.version + 1
            database.sql(
                """
                update user_quota
                set bonus_limit = :bonusLimit, policy_version = :policyVersion,
                    version = :nextVersion, updated_at = :now
                where user_id = :userId and version = :version
                """.trimIndent(),
            ).bind("bonusLimit", nextBonusLimit).bind("policyVersion", MonthlyQuestionQuotaPolicy.VERSION)
                .bind("nextVersion", nextVersion).bind("now", now).bind("userId", userId)
                .bind("version", quota.version).fetch().rowsUpdated().awaitSingle()
            database.sql(
                """
                insert into user_quota_history (
                    event_id, user_id, event_type,
                    affected_period_started_at, affected_period_ends_at, applied_to_current,
                    tier_code_before, tier_code_after, base_limit_before, base_limit_after,
                    bonus_limit_before, bonus_limit_after,
                    committed_count_before, committed_count_after,
                    reserved_count_before, reserved_count_after,
                    committed_delta, reserved_delta, bonus_delta,
                    reason, quota_version_after, occurred_at, created_at
                ) values (
                    :eventId, :userId, 'ADMIN_ADJUSTED',
                    :periodStartedAt, :periodEndsAt, true,
                    :tierCode, :tierCode, :baseLimit, :baseLimit,
                    :bonusBefore, :bonusAfter, :committed, :committed, :reserved, :reserved,
                    0, 0, :bonusDelta, :reason, :nextVersion, :now, :now
                )
                """.trimIndent(),
            ).bind("eventId", "admin-limit:$userId:$nextVersion").bind("userId", userId)
                .bind("periodStartedAt", quota.periodStartedAt).bind("periodEndsAt", quota.periodEndsAt)
                .bind("tierCode", quota.tierCode).bind("baseLimit", quota.baseLimit)
                .bind("bonusBefore", quota.bonusLimit).bind("bonusAfter", nextBonusLimit)
                .bind("bonusDelta", nextBonusLimit - quota.bonusLimit).bind("committed", quota.committedCount)
                .bind("reserved", quota.reservedCount)
                .bind("reason", "Administrator changed the current-period quota bonus")
                .bind("nextVersion", nextVersion).bind("now", now)
                .fetch().rowsUpdated().awaitSingle()
        }
        return user(userId)
    }

    private suspend fun synchronizeAdminPlan(userId: Long, tierCode: String, baseLimit: Int, now: Instant) {
        if (!ensureAdminQuotaExists(userId, tierCode, baseLimit, now)) return
        quotaRollover.rolloverUserIfDue(userId, now)
        val quota = database.sql(
            """
            select tier_code, period_started_at, period_ends_at,
                   base_limit, bonus_limit, committed_count, reserved_count, version
            from user_quota where user_id = :userId for update
            """.trimIndent(),
        ).bind("userId", userId).map { row, _ ->
            AdminQuotaMutationRow(
                tierCode = row.string("tier_code"),
                periodStartedAt = row.instant("period_started_at"),
                periodEndsAt = row.instant("period_ends_at"),
                baseLimit = row.int("base_limit"),
                bonusLimit = row.int("bonus_limit"),
                committedCount = row.int("committed_count"),
                reservedCount = row.int("reserved_count"),
                version = row.long("version"),
            )
        }.one().awaitSingleOrNull() ?: return
        if (quota.tierCode == tierCode && quota.baseLimit == baseLimit) return
        val nextVersion = quota.version + 1
        database.sql(
            """
            update user_quota set tier_code = :tierCode, base_limit = :baseLimit,
                policy_version = :policyVersion, version = :nextVersion, updated_at = :now
            where user_id = :userId and version = :version
            """.trimIndent(),
        ).bind("tierCode", tierCode).bind("baseLimit", baseLimit)
            .bind("policyVersion", MonthlyQuestionQuotaPolicy.VERSION).bind("nextVersion", nextVersion)
            .bind("now", now).bind("userId", userId).bind("version", quota.version)
            .fetch().rowsUpdated().awaitSingle()
        val eventType = if (
            MonthlyQuestionQuotaPolicy.isUpgrade(quota.tierCode, tierCode) ||
            (quota.tierCode == tierCode && baseLimit > quota.baseLimit)
        ) {
            "PLAN_UPGRADED"
        } else {
            "PLAN_DOWNGRADED"
        }
        database.sql(
            """
            insert into user_quota_history (
                event_id, user_id, event_type,
                affected_period_started_at, affected_period_ends_at, applied_to_current,
                tier_code_before, tier_code_after, base_limit_before, base_limit_after,
                bonus_limit_before, bonus_limit_after,
                committed_count_before, committed_count_after,
                reserved_count_before, reserved_count_after,
                committed_delta, reserved_delta, bonus_delta,
                reason, quota_version_after, occurred_at, created_at
            ) values (
                :eventId, :userId, :eventType, :periodStartedAt, :periodEndsAt, true,
                :tierBefore, :tierAfter, :baseBefore, :baseAfter,
                :bonus, :bonus, :committed, :committed, :reserved, :reserved,
                0, 0, 0, :reason, :nextVersion, :now, :now
            )
            """.trimIndent(),
        ).bind("eventId", "admin-plan:$userId:$nextVersion").bind("userId", userId)
            .bind("eventType", eventType).bind("periodStartedAt", quota.periodStartedAt)
            .bind("periodEndsAt", quota.periodEndsAt).bind("tierBefore", quota.tierCode)
            .bind("tierAfter", tierCode).bind("baseBefore", quota.baseLimit).bind("baseAfter", baseLimit)
            .bind("bonus", quota.bonusLimit).bind("committed", quota.committedCount)
            .bind("reserved", quota.reservedCount)
            .bind("reason", "Administrator changed effective quota plan; usage preserved")
            .bind("nextVersion", nextVersion).bind("now", now)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun ensureAdminQuotaExists(
        userId: Long,
        tierCodeHint: String? = null,
        baseLimitHint: Int? = null,
        now: Instant,
    ): Boolean {
        val createdAt = database.sql("select created_at from users where id = :userId for update")
            .bind("userId", userId)
            .map { row, _ -> row.instant("created_at") }
            .one().awaitSingleOrNull() ?: return false
        val exists = database.sql("select exists(select 1 from user_quota where user_id = :userId) as present")
            .bind("userId", userId).map { row, _ ->
                row.get("present", java.lang.Boolean::class.java)?.booleanValue() == true
            }.one().awaitSingle()
        if (exists) return true

        val plan = if (tierCodeHint != null && baseLimitHint != null) {
            tierCodeHint to baseLimitHint
        } else {
            database.sql(
                """
                select coalesce(e.tier_code, 'TIER1') as tier_code,
                       coalesce(t.monthly_question_limit, fallback.monthly_question_limit, 30) as base_limit
                from users u
                left join user_entitlement_projection e on e.user_id = u.id
                left join user_membership_tiers t on t.tier_code = coalesce(e.tier_code, 'TIER1')
                left join user_membership_tiers fallback on fallback.tier_code = 'TIER1'
                where u.id = :userId
                """.trimIndent(),
            ).bind("userId", userId).map { row, _ ->
                row.string("tier_code") to row.int("base_limit")
            }.one().awaitSingleOrNull() ?: return false
        }
        val window = MonthlyQuotaWindow.periodAt(createdAt, now)
        database.sql(
            """
            insert into user_quota (
                user_id, tier_code, anchor_type, anchor_at, anchor_day, first_paid_at,
                period_started_at, period_ends_at, base_limit, bonus_limit,
                committed_count, reserved_count, policy_version, version, created_at, updated_at
            ) values (
                :userId, :tierCode, 'ACCOUNT_CREATED', :anchorAt, :anchorDay, null,
                :periodStartedAt, :periodEndsAt, :baseLimit, 0,
                0, 0, :policyVersion, 0, :now, :now
            )
            """.trimIndent(),
        ).bind("userId", userId).bind("tierCode", plan.first)
            .bind("anchorAt", createdAt).bind("anchorDay", createdAt.atZone(ZoneOffset.UTC).dayOfMonth)
            .bind("periodStartedAt", window.startedAt).bind("periodEndsAt", window.resetAt)
            .bind("baseLimit", plan.second).bind("policyVersion", MonthlyQuestionQuotaPolicy.VERSION)
            .bind("now", now).fetch().rowsUpdated().awaitSingle()
        database.sql(
            """
            insert into user_quota_history (
                event_id, user_id, event_type,
                affected_period_started_at, affected_period_ends_at, applied_to_current,
                tier_code_after, base_limit_after, bonus_limit_after,
                committed_count_after, reserved_count_after,
                committed_delta, reserved_delta, bonus_delta,
                reason, quota_version_after, occurred_at, created_at
            ) values (
                :eventId, :userId, 'QUOTA_CREATED',
                :periodStartedAt, :periodEndsAt, true,
                :tierCode, :baseLimit, 0, 0, 0,
                0, 0, 0, 'Initial quota projection created by an administrator action',
                0, :now, :now
            )
            """.trimIndent(),
        ).bind("eventId", "quota-created:$userId").bind("userId", userId)
            .bind("periodStartedAt", window.startedAt).bind("periodEndsAt", window.resetAt)
            .bind("tierCode", plan.first).bind("baseLimit", plan.second).bind("now", now)
            .fetch().rowsUpdated().awaitSingle()
        return true
    }

    private fun userSelect(): String =
        """
        select
            u.id,
            u.email,
            u.display_name,
            u.provider,
            u.status,
            u.created_at,
            coalesce(uq.tier_code, e.tier_code, 'TIER1') as tier_code,
            coalesce(t.description, fallback_tier.description, '') as tier_description,
            coalesce(uq.base_limit, t.monthly_question_limit, fallback_tier.monthly_question_limit, 30) as monthly_limit,
            coalesce(uq.anchor_at, u.created_at) as quota_anchor_at,
            coalesce(uq.policy_version, ${MonthlyQuestionQuotaPolicy.VERSION}) as quota_policy_version,
            d.app_version,
            d.app_build,
            d.app_version_seen_at
        from users u
        left join user_entitlement_projection e on e.user_id = u.id
        left join user_quota uq on uq.user_id = u.id
        left join user_membership_tiers t on t.tier_code = coalesce(uq.tier_code, e.tier_code, 'TIER1')
        left join user_membership_tiers fallback_tier on fallback_tier.tier_code = 'TIER1'
        left join devices d on d.id = (
            select latest_device.id
            from devices latest_device
            where latest_device.user_id = u.id
            order by latest_device.app_version_seen_at desc, latest_device.last_seen_at desc, latest_device.id desc
            limit 1
        )
        """.trimIndent()

    private fun DatabaseClient.GenericExecuteSpec.bindSearch(search: String?, exact: String?): DatabaseClient.GenericExecuteSpec {
        if (search == null) return this
        return bind("query", search).bind("exactQuery", exact.orEmpty())
    }

    private suspend fun currentUsageByUser(rows: List<AdminUserRow>, now: Instant): Map<Long, CurrentPeriodUsage> {
        if (rows.isEmpty()) return emptyMap()
        val periods = rows.associate { it.id to MonthlyQuotaWindow.periodAt(it.quotaAnchorAt, now) }
        val conditions = rows.indices.joinToString(" or ") { index -> "user_id = :userId$index" }
        var spec = database.sql(
            """
            select user_id, period_started_at, period_ends_at,
                   committed_count, reserved_count, bonus_limit, policy_version
            from user_quota
            where $conditions
            """.trimIndent(),
        )
        rows.forEachIndexed { index, row ->
            spec = spec.bind("userId$index", row.id)
        }
        return spec.map { result, _ ->
            val userId = result.long("user_id")
            val expected = periods.getValue(userId)
            val isCurrent = result.instant("period_started_at") == expected.startedAt &&
                result.instant("period_ends_at") == expected.resetAt
            userId to if (isCurrent) {
                CurrentPeriodUsage(
                    committedCount = result.int("committed_count"),
                    reservedCount = result.int("reserved_count"),
                    bonusCount = result.int("bonus_limit"),
                    policyVersion = result.int("policy_version"),
                )
            } else {
                CurrentPeriodUsage(policyVersion = result.int("policy_version"))
            }
        }.all().collectList().awaitSingle().toMap()
    }

    private fun Row.toAdminUserRow(): AdminUserRow =
        AdminUserRow(
            id = long("id"),
            email = string("email"),
            displayName = string("display_name"),
            provider = string("provider"),
            status = string("status"),
            tierCode = string("tier_code"),
            tierDescription = string("tier_description"),
            monthlyLimit = int("monthly_limit"),
            quotaAnchorAt = instant("quota_anchor_at"),
            quotaPolicyVersion = int("quota_policy_version"),
            createdAt = instant("created_at"),
            appVersion = get("app_version", String::class.java),
            appBuild = get("app_build", String::class.java),
            appVersionSeenAt = nullableInstant("app_version_seen_at"),
        )

    private fun AdminUserRow.toAdminUser(usage: CurrentPeriodUsage, now: Instant): AdminUserSummary {
        val period = MonthlyQuotaWindow.periodAt(quotaAnchorAt, now)
        val effectiveLimit = (monthlyLimit + usage.bonusCount).coerceAtLeast(0)
        return AdminUserSummary(
            id = id,
            email = email,
            displayName = displayName,
            provider = provider,
            status = status,
            tierCode = tierCode,
            tierDescription = tierDescription,
            monthlyLimit = effectiveLimit,
            monthlyLimitOverride = null,
            currentPeriodQuestionLimitOverride = effectiveLimit.takeIf { usage.bonusCount > 0 },
            baseLimit = monthlyLimit,
            bonusLimit = usage.bonusCount,
            usedCount = usage.committedCount,
            reservedCount = usage.reservedCount,
            remainingCount = (effectiveLimit - usage.committedCount - usage.reservedCount).coerceAtLeast(0),
            quotaPolicyVersion = usage.policyVersion.takeIf { it > 0 } ?: quotaPolicyVersion,
            periodStartedAt = period.startedAt,
            resetAt = period.resetAt,
            createdAt = createdAt,
            appVersion = appVersion,
            appBuild = appBuild,
            appVersionSeenAt = appVersionSeenAt,
        )
    }

    private data class AdminUserRow(
        val id: Long,
        val email: String,
        val displayName: String,
        val provider: String,
        val status: String,
        val tierCode: String,
        val tierDescription: String,
        val monthlyLimit: Int,
        val quotaAnchorAt: Instant,
        val quotaPolicyVersion: Int,
        val createdAt: Instant,
        val appVersion: String?,
        val appBuild: String?,
        val appVersionSeenAt: Instant?,
    )

    private data class CurrentPeriodUsage(
        val committedCount: Int = 0,
        val reservedCount: Int = 0,
        val bonusCount: Int = 0,
        val policyVersion: Int = MonthlyQuestionQuotaPolicy.VERSION,
    )

    private data class AdminQuotaMutationRow(
        val tierCode: String,
        val periodStartedAt: Instant,
        val periodEndsAt: Instant,
        val baseLimit: Int,
        val bonusLimit: Int,
        val committedCount: Int,
        val reservedCount: Int,
        val version: Long,
    )

    private fun Row.string(name: String): String = get(name, String::class.java).orEmpty()
    private fun Row.int(name: String): Int = get(name, java.lang.Integer::class.java)?.toInt() ?: 0
    private fun Row.long(name: String): Long = get(name, java.lang.Long::class.java)?.toLong() ?: 0L
    private fun Row.instant(name: String): Instant =
        nullableInstant(name) ?: Instant.EPOCH
    private fun Row.nullableInstant(name: String): Instant? =
        get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
}
