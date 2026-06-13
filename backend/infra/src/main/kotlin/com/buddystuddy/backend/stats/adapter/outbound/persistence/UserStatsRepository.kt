package com.buddystuddy.backend.stats.adapter.outbound.persistence

import com.buddystuddy.backend.stats.application.port.outbound.UserStatsPort
import com.buddystuddy.stats.domain.entity.UserStatsEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
interface UserStatsJpaRepository : JpaRepository<UserStatsEntity, Long> {
    @Query(
        """
        select s from UserStatsEntity s
        where s.userId = :userId
          and s.statDate >= :startDate
          and s.statDate < :endDate
        """
    )
    fun findStatsRows(
        @Param("userId") userId: Long,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
    ): List<UserStatsEntity>

    @Query(
        """
        select s from UserStatsEntity s
        where s.userId = :userId
          and s.statDate >= :startDate
          and s.statDate < :endDate
          and (
            lower(s.topic) like concat('%', lower(:query), '%')
            or lower(s.topicKey) like concat('%', lower(:query), '%')
          )
        """
    )
    fun findStatsRowsByQuery(
        @Param("userId") userId: Long,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
        @Param("query") query: String,
    ): List<UserStatsEntity>
}

@Repository
class UserStatsRepository(
    private val jpa: UserStatsJpaRepository,
) : UserStatsPort {
    @Transactional
    override fun replaceAll(rows: Collection<UserStatsEntity>) {
        jpa.deleteAllInBatch()
        jpa.saveAll(rows)
    }

    override fun findByUser(userId: Long, startDate: LocalDate?, endDate: LocalDate?, query: String?): List<UserStatsEntity> {
        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedStartDate = startDate ?: MIN_STATS_DATE
        val normalizedEndDate = endDate ?: MAX_STATS_DATE
        return if (normalizedQuery == null) {
            jpa.findStatsRows(userId, normalizedStartDate, normalizedEndDate)
        } else {
            jpa.findStatsRowsByQuery(userId, normalizedStartDate, normalizedEndDate, normalizedQuery)
        }
    }

    private companion object {
        val MIN_STATS_DATE: LocalDate = LocalDate.of(1970, 1, 1)
        val MAX_STATS_DATE: LocalDate = LocalDate.of(9999, 12, 31)
    }
}
