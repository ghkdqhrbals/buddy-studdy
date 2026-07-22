package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.account.domain.entity.UserMonthlyQuestionUsageEntity
import com.buddystudy.backend.config.saveEntity
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPlan
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.YearMonth

@Repository
class QuestionMembershipPersistenceAdapter(
    private val memberships: UserMembershipRepository,
    private val tiers: UserMembershipTierRepository,
    private val template: R2dbcEntityTemplate,
    connectionFactory: ConnectionFactory,
) : QuestionMembershipPort {
    private val postgresDatabase = connectionFactory.metadata.name.contains("PostgreSQL", ignoreCase = true)

    override suspend fun activePlanForUser(userId: Long): QuestionMembershipPlan? {
        val membership = memberships.findFirstByUserIdAndStatusOrderByUpdatedAtDesc(userId, "ACTIVE")
        val now = Instant.now()
        val tierCode = if (
            membership == null || membership.startedAt.isAfter(now) || membership.expiresAt?.isAfter(now) == false
        ) DEFAULT_TIER else membership.tier
        val tier = tiers.findByTierCode(tierCode) ?: tiers.findByTierCode(DEFAULT_TIER) ?: return null
        return QuestionMembershipPlan(tier.tierCode, tier.monthlyQuestionLimit)
    }

    @Transactional
    override suspend fun tryConsumeMonthlySystemQuestion(
        userId: Long,
        yearMonth: YearMonth,
        limit: Int,
        now: Instant,
    ): Boolean {
        if (limit <= 0) return false
        if (!postgresDatabase) return consumePortable(userId, yearMonth, limit, now)
        return template.databaseClient.sql(
            """
            insert into user_monthly_question_usage
                (user_id, year_month, system_question_count, created_at, updated_at)
            values (:userId, :yearMonth, 1, :now, :now)
            on conflict (user_id, year_month) do update
            set system_question_count = user_monthly_question_usage.system_question_count + 1,
                updated_at = :now
            where user_monthly_question_usage.system_question_count < :limit
            returning system_question_count
            """.trimIndent(),
        ).bind("userId", userId).bind("yearMonth", yearMonth.toString()).bind("limit", limit).bind("now", now)
            .map { _, _ -> true }.one().awaitSingleOrNull() == true
    }

    @Transactional
    override suspend fun refundMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, now: Instant) {
        template.databaseClient.sql(
            """
            update user_monthly_question_usage
            set system_question_count = case when system_question_count > 0 then system_question_count - 1 else 0 end,
                updated_at = :now
            where user_id = :userId and year_month = :yearMonth and system_question_count > 0
            """.trimIndent(),
        ).bind("userId", userId).bind("yearMonth", yearMonth.toString()).bind("now", now)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun consumePortable(userId: Long, yearMonth: YearMonth, limit: Int, now: Instant): Boolean {
        val query = Query.query(
            Criteria.where("user_id").`is`(userId).and("year_month").`is`(yearMonth.toString()),
        )
        val current = template.selectOne(query, UserMonthlyQuestionUsageEntity::class.java).awaitSingleOrNull()
        if (current == null) {
            template.saveEntity(
                UserMonthlyQuestionUsageEntity(
                    userId = userId, yearMonth = yearMonth.toString(), systemQuestionCount = 1,
                    createdAt = now, updatedAt = now,
                ),
                0,
            )
            return true
        }
        if (current.systemQuestionCount >= limit) return false
        current.systemQuestionCount += 1
        current.updatedAt = now
        template.saveEntity(current, current.id)
        return true
    }

    private companion object {
        const val DEFAULT_TIER = "TIER1"
    }
}
