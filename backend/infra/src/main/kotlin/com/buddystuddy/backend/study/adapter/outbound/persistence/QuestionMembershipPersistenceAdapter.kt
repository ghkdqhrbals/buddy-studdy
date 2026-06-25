package com.buddystuddy.backend.study.adapter.outbound.persistence

import com.buddystuddy.account.domain.entity.UserMonthlyQuestionUsageEntity
import com.buddystuddy.backend.study.application.port.outbound.QuestionMembershipPort
import jakarta.persistence.EntityManager
import jakarta.persistence.NoResultException
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.time.YearMonth
import javax.sql.DataSource

interface UserMonthlyQuestionUsageRepository : JpaRepository<UserMonthlyQuestionUsageEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByUserIdAndYearMonth(userId: Long, yearMonth: String): UserMonthlyQuestionUsageEntity?
}

@Repository
class QuestionMembershipPersistenceAdapter(
    private val memberships: UserMembershipRepository,
    private val usages: UserMonthlyQuestionUsageRepository,
    private val entityManager: EntityManager,
    private val dataSource: DataSource,
) : QuestionMembershipPort {
    private val postgresDatabase: Boolean by lazy { detectPostgres() }

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
        if (!postgresDatabase) {
            return tryConsumeMonthlySystemQuestionWithJpa(userId, yearMonth, limit, now)
        }
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

    @Transactional
    override fun refundMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, now: Instant) {
        if (!postgresDatabase) {
            refundMonthlySystemQuestionWithJpa(userId, yearMonth, now)
            return
        }
        val sql = """
            update user_monthly_question_usage
            set system_question_count = greatest(system_question_count - 1, 0),
                updated_at = :now
            where user_id = :userId
              and year_month = :yearMonth
              and system_question_count > 0
        """.trimIndent()
        entityManager.createNativeQuery(sql)
            .setParameter("userId", userId)
            .setParameter("yearMonth", yearMonth.toString())
            .setParameter("now", Timestamp.from(now))
            .executeUpdate()
    }

    private fun tryConsumeMonthlySystemQuestionWithJpa(userId: Long, yearMonth: YearMonth, limit: Int, now: Instant): Boolean {
        val yearMonthValue = yearMonth.toString()
        val existing = usages.findByUserIdAndYearMonth(userId, yearMonthValue)
        if (existing == null) {
            usages.save(
                UserMonthlyQuestionUsageEntity(
                    userId = userId,
                    yearMonth = yearMonthValue,
                    systemQuestionCount = 1,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            return true
        }
        if (existing.systemQuestionCount >= limit) return false
        existing.systemQuestionCount += 1
        existing.updatedAt = now
        usages.save(existing)
        return true
    }

    private fun refundMonthlySystemQuestionWithJpa(userId: Long, yearMonth: YearMonth, now: Instant) {
        val existing = usages.findByUserIdAndYearMonth(userId, yearMonth.toString()) ?: return
        if (existing.systemQuestionCount <= 0) return
        existing.systemQuestionCount -= 1
        existing.updatedAt = now
        usages.save(existing)
    }

    private fun detectPostgres(): Boolean =
        dataSource.connection.use { connection ->
            connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)
        }
}
