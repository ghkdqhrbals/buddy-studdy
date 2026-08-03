package com.buddystudy.backend.admin.management.adapter.outbound.persistence

import com.buddystudy.backend.admin.management.application.model.AdminMembershipTierResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserSummary
import com.buddystudy.backend.admin.management.application.model.AssignUserPlanCommand
import com.buddystudy.backend.admin.management.application.port.outbound.AdminManagementPort
import com.buddystudy.backend.common.application.quota.MonthlyQuotaWindow
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

@Repository
class AdminManagementPersistenceAdapter(
    private val database: DatabaseClient,
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

    override suspend fun updateTier(tierCode: String, monthlyQuestionLimit: Int): AdminMembershipTierResponse? {
        val changed = database.sql(
            """
            update user_membership_tiers
            set monthly_question_limit = :limit, updated_at = :now
            where tier_code = :tierCode
            """.trimIndent(),
        ).bind("limit", monthlyQuestionLimit)
            .bind("now", Instant.now())
            .bind("tierCode", tierCode)
            .fetch().rowsUpdated().awaitSingle()
        return if (changed == 0L) null else tiers().firstOrNull { it.tierCode == tierCode }
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
        return user(userId)
    }

    @Transactional
    override suspend fun setCurrentPeriodQuestionLimit(
        userId: Long,
        questionLimitOverride: Int?,
    ): AdminUserSummary? {
        val createdAt = database.sql(
            "select created_at from users where id = :userId and status <> 'ANONYMOUS'",
        ).bind("userId", userId)
            .map { row, _ -> row.instant("created_at") }
            .one()
            .awaitSingleOrNull()
            ?: return null
        val now = Instant.now()
        val period = MonthlyQuotaWindow.periodAt(createdAt, now)
        val periodStartedAt = LocalDateTime.ofInstant(period.startedAt, ZoneOffset.UTC)

        if (questionLimitOverride == null) {
            database.sql(
                """
                update user_monthly_question_usage
                set current_period_question_limit_override = null, updated_at = :now
                where user_id = :userId and period_start = :periodStartedAt
                """.trimIndent(),
            ).bind("now", now)
                .bind("userId", userId)
                .bind("periodStartedAt", periodStartedAt)
                .fetch().rowsUpdated().awaitSingle()
        } else {
            database.sql(
                """
                insert into user_monthly_question_usage
                    (user_id, usage_month, period_start, system_question_count,
                     current_period_question_limit_override, created_at, updated_at)
                values (:userId, :usageMonth, :periodStartedAt, 0, :questionLimitOverride, :now, :now)
                on duplicate key update
                    current_period_question_limit_override = :questionLimitOverride,
                    updated_at = :now
                """.trimIndent(),
            ).bind("userId", userId)
                .bind("usageMonth", YearMonth.from(period.startedAt.atZone(ZoneOffset.UTC)).toString())
                .bind("periodStartedAt", periodStartedAt)
                .bind("questionLimitOverride", questionLimitOverride)
                .bind("now", now)
                .fetch().rowsUpdated().awaitSingle()
        }
        return user(userId)
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
            coalesce(m.tier, 'TIER1') as tier_code,
            coalesce(t.description, fallback_tier.description, '') as tier_description,
            m.monthly_question_limit_override,
            coalesce(m.monthly_question_limit_override, t.monthly_question_limit, fallback_tier.monthly_question_limit, 0) as monthly_limit,
            d.app_version,
            d.app_build,
            d.app_version_seen_at
        from users u
        left join user_memberships m on m.id = (
            select max(active_membership.id)
            from user_memberships active_membership
            where active_membership.user_id = u.id
              and active_membership.status = 'ACTIVE'
              and active_membership.started_at <= utc_timestamp(6)
              and (active_membership.expires_at is null or active_membership.expires_at > utc_timestamp(6))
        )
        left join user_membership_tiers t on t.tier_code = m.tier
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
        val periods = rows.associate { it.id to MonthlyQuotaWindow.periodAt(it.createdAt, now) }
        val conditions = rows.indices.joinToString(" or ") { index ->
            "(user_id = :userId$index and period_start = :periodStartedAt$index)"
        }
        var spec = database.sql(
            """
            select user_id, system_question_count, current_period_question_limit_override
            from user_monthly_question_usage
            where $conditions
            """.trimIndent(),
        )
        rows.forEachIndexed { index, row ->
            spec = spec.bind("userId$index", row.id)
                .bind(
                    "periodStartedAt$index",
                    LocalDateTime.ofInstant(periods.getValue(row.id).startedAt, ZoneOffset.UTC),
                )
        }
        return spec.map { result, _ ->
            result.long("user_id") to CurrentPeriodUsage(
                usedCount = result.int("system_question_count"),
                questionLimitOverride = result.get(
                    "current_period_question_limit_override",
                    java.lang.Integer::class.java,
                )?.toInt(),
            )
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
            monthlyLimitOverride = get("monthly_question_limit_override", java.lang.Integer::class.java)?.toInt(),
            createdAt = instant("created_at"),
            appVersion = get("app_version", String::class.java),
            appBuild = get("app_build", String::class.java),
            appVersionSeenAt = nullableInstant("app_version_seen_at"),
        )

    private fun AdminUserRow.toAdminUser(usage: CurrentPeriodUsage, now: Instant): AdminUserSummary {
        val period = MonthlyQuotaWindow.periodAt(createdAt, now)
        val effectiveLimit = usage.questionLimitOverride ?: monthlyLimit
        return AdminUserSummary(
            id = id,
            email = email,
            displayName = displayName,
            provider = provider,
            status = status,
            tierCode = tierCode,
            tierDescription = tierDescription,
            monthlyLimit = effectiveLimit,
            monthlyLimitOverride = monthlyLimitOverride,
            currentPeriodQuestionLimitOverride = usage.questionLimitOverride,
            usedCount = usage.usedCount,
            remainingCount = (effectiveLimit - usage.usedCount).coerceAtLeast(0),
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
        val monthlyLimitOverride: Int?,
        val createdAt: Instant,
        val appVersion: String?,
        val appBuild: String?,
        val appVersionSeenAt: Instant?,
    )

    private data class CurrentPeriodUsage(
        val usedCount: Int = 0,
        val questionLimitOverride: Int? = null,
    )

    private fun Row.string(name: String): String = get(name, String::class.java).orEmpty()
    private fun Row.int(name: String): Int = get(name, java.lang.Integer::class.java)?.toInt() ?: 0
    private fun Row.long(name: String): Long = get(name, java.lang.Long::class.java)?.toLong() ?: 0L
    private fun Row.instant(name: String): Instant =
        nullableInstant(name) ?: Instant.EPOCH
    private fun Row.nullableInstant(name: String): Instant? =
        get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
}
