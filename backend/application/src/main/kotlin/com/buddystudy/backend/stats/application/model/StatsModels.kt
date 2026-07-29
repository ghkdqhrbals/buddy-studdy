package com.buddystudy.backend.stats.application.model

import com.buddystudy.backend.common.application.model.PageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import java.time.Instant
import java.time.LocalDate

data class StatsQuery(
    val search: String? = null,
    val period: String? = null,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
)

data class TopicLevelRangeResponse(
    val level: Int,
    val average: Int,
    val sampleCount: Int,
    val centerLevel: Double,
    val lowerBound: Double,
    val upperBound: Double,
)

data class TopicStatsResponse(
    val topicKey: String,
    val topic: String,
    val topicAliases: List<String>,
    val count: Int,
    val average: Int,
    val best: Int,
    val correctRate: Int,
    val levelRange: TopicLevelRangeResponse,
    val latestAt: Instant,
    val records: List<StudyRecordResponse>,
)

data class StatsResponse(
    val totalResponses: Int,
    val totalTopics: Int,
    val topics: List<TopicStatsResponse>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
    val generatedAt: Instant,
) : PageResponse

data class StatsActivityDayResponse(
    val date: LocalDate,
    val answerCount: Int,
    val topicCount: Int,
    val topics: List<String>,
    val bestLevel: Double?,
)

data class StatsActivityResponse(
    val days: List<StatsActivityDayResponse>,
    val streakDays: Int,
    val monthAnswerCount: Int,
    val generatedAt: Instant,
)

data class StudyGrowthResponse(
    val roots: List<StudyGrowthRootResponse>,
    val nodes: List<StudyGrowthNodeResponse>,
    val startAt: Instant,
    val endAt: Instant,
    val generatedAt: Instant,
)

data class StudyGrowthRootResponse(
    val studyId: Long,
    val topic: String,
    val activeForQuestions: Boolean,
    val currentLevel: Double?,
    val previousLevel: Double?,
    val growth: Double?,
    val answerCount: Int,
    val measuredTopicCount: Int,
    val totalTopicCount: Int,
    val trend: List<Double>,
    val trendPoints: List<StudyGrowthTrendPointResponse>,
    val profile: StudyGrowthProfileResponse,
)

data class StudyGrowthProfileResponse(
    val achievement: Double?,
    val challenge: Double?,
    val completion: Double?,
    val breadth: Double?,
    val depth: Double?,
)

data class StudyGrowthNodeResponse(
    val studyId: Long,
    val parentStudyId: Long?,
    val rootStudyId: Long,
    val topic: String,
    val sortOrder: Int,
    val depth: Int,
    val childCount: Int,
    val activeForQuestions: Boolean,
    val currentLevel: Double?,
    val previousLevel: Double?,
    val growth: Double?,
    val answerCount: Int,
    val measuredTopicCount: Int,
    val totalTopicCount: Int,
    val latestAt: Instant?,
    val trend: List<Double>,
    val trendPoints: List<StudyGrowthTrendPointResponse>,
)

data class StudyGrowthTrendPointResponse(
    val measuredAt: Instant,
    val level: Double,
)
