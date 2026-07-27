package com.buddystudy.backend.stats.application.port.outbound

import com.buddystudy.stats.domain.entity.UserStatsEntity
import java.time.Instant
import java.time.LocalDate

interface UserStatsPort {
    suspend fun replaceAll(rows: Collection<UserStatsEntity>)
    suspend fun syncAll(rows: Collection<UserStatsEntity>)
    suspend fun findByUser(userId: Long, startDate: LocalDate?, endDate: LocalDate?, query: String?): List<UserStatsEntity>
    suspend fun overviewByUser(userId: Long, startDate: LocalDate?, endDate: LocalDate?, query: String?): UserStatsOverview
    suspend fun findTopicKeysByUser(
        userId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        query: String?,
        limit: Int,
        offset: Int,
    ): List<String>
    suspend fun findByUserAndTopicKeys(
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

interface StudyGrowthStatsPort {
    suspend fun findByUser(
        userId: Long,
        startAt: Instant,
        endAt: Instant,
    ): List<StudyGrowthRecord>
}

data class StudyGrowthRecord(
    val studyId: Long,
    val difficultyLevel: Int,
    val score: Int,
    val answeredAt: Instant,
)
