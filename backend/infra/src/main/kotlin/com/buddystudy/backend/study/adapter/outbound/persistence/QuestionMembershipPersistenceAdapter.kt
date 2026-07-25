package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPlan
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionQuotaStatus
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.YearMonth

@Repository
class QuestionMembershipPersistenceAdapter(
    private val memberships: UserMembershipRepository,
    private val tiers: UserMembershipTierRepository,
    private val template: R2dbcEntityTemplate,
) : QuestionMembershipPort {

    override suspend fun activePlanForUser(userId: Long): QuestionMembershipPlan? {
        val membership = memberships.findFirstByUserIdAndStatusOrderByUpdatedAtDesc(userId, "ACTIVE")
        val now = Instant.now()
        val tierCode = if (
            membership == null || membership.startedAt.isAfter(now) || membership.expiresAt?.isAfter(now) == false
        ) DEFAULT_TIER else membership.tier
        val tier = tiers.findByTierCode(tierCode) ?: tiers.findByTierCode(DEFAULT_TIER) ?: return null
        return QuestionMembershipPlan(
            tierCode = tier.tierCode,
            monthlyQuestionLimit = membership?.monthlyQuestionLimitOverride ?: tier.monthlyQuestionLimit,
        )
    }

    override suspend fun quotaStatusForUser(userId: Long, yearMonth: YearMonth): QuestionQuotaStatus? {
        val plan = activePlanForUser(userId) ?: return null
        val used = template.databaseClient.sql(
            """
            select system_question_count
            from user_monthly_question_usage
            where user_id = :userId and usage_month = :yearMonth
            """.trimIndent(),
        ).bind("userId", userId).bind("yearMonth", yearMonth.toString())
            .map { row, _ -> row.get("system_question_count", java.lang.Integer::class.java)?.toInt() ?: 0 }
            .one()
            .awaitSingleOrNull()
            ?: 0
        return QuestionQuotaStatus(plan.tierCode, used, plan.monthlyQuestionLimit)
    }

    @Transactional
    override suspend fun tryConsumeMonthlySystemQuestion(
        userId: Long,
        yearMonth: YearMonth,
        limit: Int,
        now: Instant,
    ): Boolean {
        if (limit <= 0) return false
        val changed = template.databaseClient.sql(
            """
            insert into user_monthly_question_usage
                (user_id, usage_month, system_question_count, created_at, updated_at)
            values (:userId, :yearMonth, 1, :now, :now)
            on duplicate key update
                system_question_count = if(system_question_count < :limit, system_question_count + 1, system_question_count),
                updated_at = if(system_question_count < :limit, :now, updated_at)
            """.trimIndent(),
        ).bind("userId", userId).bind("yearMonth", yearMonth.toString()).bind("limit", limit).bind("now", now)
            .fetch().rowsUpdated().awaitSingle()
        return changed > 0
    }

    @Transactional
    override suspend fun refundMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, now: Instant) {
        template.databaseClient.sql(
            """
            update user_monthly_question_usage
            set system_question_count = case when system_question_count > 0 then system_question_count - 1 else 0 end,
                updated_at = :now
            where user_id = :userId and usage_month = :yearMonth and system_question_count > 0
            """.trimIndent(),
        ).bind("userId", userId).bind("yearMonth", yearMonth.toString()).bind("now", now)
            .fetch().rowsUpdated().awaitSingle()
    }

    private companion object {
        const val DEFAULT_TIER = "TIER1"
    }
}
