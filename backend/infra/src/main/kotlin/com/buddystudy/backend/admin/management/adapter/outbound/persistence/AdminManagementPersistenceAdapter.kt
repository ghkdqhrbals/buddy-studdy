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
import java.time.ZoneOffset

@Repository
class AdminManagementPersistenceAdapter(
    private val database: DatabaseClient,
) : AdminManagementPort {
    override suspend fun users(query: String?, limit: Int, offset: Int): AdminUserPageResponse {
        val search = query?.lowercase()?.let { "%$it%" }
        val where = if (search == null) "" else "where lower(u.email) like :query or lower(u.display_name) like :query or cast(u.id as char) = :exactQuery"
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
            .bind("usageMonth", MonthlyQuotaWindow.periodAt(Instant.now()).toString())
            .bind("limit", limit)
            .bind("offset", offset)
        val users = listSpec.map { row, _ -> row.toAdminUser() }.all().collectList().awaitSingle()
        return AdminUserPageResponse(users, totalCount, limit, offset)
    }

    override suspend fun user(userId: Long): AdminUserSummary? =
        database.sql(
            """
            ${userSelect()}
            where u.id = :userId
            """.trimIndent(),
        ).bind("usageMonth", MonthlyQuotaWindow.periodAt(Instant.now()).toString())
            .bind("userId", userId)
            .map { row, _ -> row.toAdminUser() }
            .one()
            .awaitSingleOrNull()

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
        val userExists = database.sql("select count(*) as count_value from users where id = :userId")
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
            coalesce(usage_row.system_question_count, 0) as used_count
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
        left join user_monthly_question_usage usage_row
          on usage_row.user_id = u.id and usage_row.usage_month = :usageMonth
        """.trimIndent()

    private fun DatabaseClient.GenericExecuteSpec.bindSearch(search: String?, exact: String?): DatabaseClient.GenericExecuteSpec {
        if (search == null) return this
        return bind("query", search).bind("exactQuery", exact.orEmpty())
    }

    private fun Row.toAdminUser(): AdminUserSummary {
        val monthlyLimit = int("monthly_limit")
        val usedCount = int("used_count")
        return AdminUserSummary(
            id = long("id"),
            email = string("email"),
            displayName = string("display_name"),
            provider = string("provider"),
            status = string("status"),
            tierCode = string("tier_code"),
            tierDescription = string("tier_description"),
            monthlyLimit = monthlyLimit,
            monthlyLimitOverride = get("monthly_question_limit_override", java.lang.Integer::class.java)?.toInt(),
            usedCount = usedCount,
            remainingCount = (monthlyLimit - usedCount).coerceAtLeast(0),
            resetAt = MonthlyQuotaWindow.resetAt(Instant.now()),
            createdAt = instant("created_at"),
        )
    }

    private fun Row.string(name: String): String = get(name, String::class.java).orEmpty()
    private fun Row.int(name: String): Int = get(name, java.lang.Integer::class.java)?.toInt() ?: 0
    private fun Row.long(name: String): Long = get(name, java.lang.Long::class.java)?.toLong() ?: 0L
    private fun Row.instant(name: String): Instant =
        get(name, Instant::class.java)
            ?: get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
            ?: Instant.EPOCH
}
