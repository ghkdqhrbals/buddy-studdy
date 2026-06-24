package com.buddystuddy.backend.study.adapter.outbound.persistence

import com.buddystuddy.backend.study.application.port.outbound.QuestionMembershipPort
import jakarta.persistence.EntityManager
import jakarta.persistence.NoResultException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.time.YearMonth

@Repository
class QuestionMembershipPersistenceAdapter(
    private val memberships: UserMembershipRepository,
    private val entityManager: EntityManager,
) : QuestionMembershipPort {
    override fun activeTierCodeForUser(userId: Long): String? {
        val membership = memberships.findFirstByUserIdAndStatusOrderByUpdatedAtDesc(userId, "ACTIVE")
            ?: return null
        val now = Instant.now()
        if (membership.startedAt.isAfter(now)) return null
        if (membership.expiresAt?.isAfter(now) == false) return null
        return membership.tier
    }

    @Transactional
    override fun tryConsumeMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, limit: Int, now: Instant): Boolean {
        if (limit <= 0) return false
        val sql = """
            insert into user_monthly_question_usage (
                user_id,
                year_month,
                system_question_count,
                created_at,
                updated_at
            )
            values (
                :userId,
                :yearMonth,
                1,
                :now,
                :now
            )
            on conflict (user_id, year_month) do update
            set system_question_count = user_monthly_question_usage.system_question_count + 1,
                updated_at = :now
            where user_monthly_question_usage.system_question_count < :limit
            returning system_question_count
        """.trimIndent()
        return try {
            entityManager.createNativeQuery(sql)
                .setParameter("userId", userId)
                .setParameter("yearMonth", yearMonth.toString())
                .setParameter("limit", limit)
                .setParameter("now", Timestamp.from(now))
                .singleResult != null
        } catch (_: NoResultException) {
            false
        }
    }
}
