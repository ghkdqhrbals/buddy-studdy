package com.buddystudy.backend.stats.application.port.outbound

import com.buddystudy.stats.domain.entity.UserStatsEntity
import java.time.LocalDate

interface UserStatsPort {
    fun replaceAll(rows: Collection<UserStatsEntity>)
    fun syncAll(rows: Collection<UserStatsEntity>)
    fun findByUser(userId: Long, startDate: LocalDate?, endDate: LocalDate?, query: String?): List<UserStatsEntity>
    fun overviewByUser(userId: Long, startDate: LocalDate?, endDate: LocalDate?, query: String?): UserStatsOverview
    fun findTopicKeysByUser(
        userId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        query: String?,
        limit: Int,
        offset: Int,
    ): List<String>
    fun findByUserAndTopicKeys(
        userId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        query: String?,
        topicKeys: Collection<String>,
    ): List<UserStatsEntity>
}

data class UserStatsOverview(
    val totalResponses: Int,
    val totalTopics: Long,
)
