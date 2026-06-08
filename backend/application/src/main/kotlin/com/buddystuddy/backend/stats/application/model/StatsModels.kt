package com.buddystuddy.backend.stats.application.model

import com.buddystuddy.backend.study.application.model.StudyRecordResponse
import java.time.Instant

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
    val limit: Int,
    val offset: Int,
    val generatedAt: Instant,
)
