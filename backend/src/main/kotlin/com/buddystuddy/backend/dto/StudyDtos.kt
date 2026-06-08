package com.buddystuddy.backend.dto

import java.time.Instant

data class CreateQuestionRequest(val topic: String? = null)
data class AnswerRequest(val answer: String)
data class RecordPublicityRequest(val isPublic: Boolean)

data class QuestionItemResponse(val question: String, val expectedAnswerHint: String? = null, val createdAt: Instant)
data class GradingResultResponse(val score: Int, val isCorrect: Boolean, val feedback: String, val explanation: String)

data class StudyRecordResponse(
    val id: String,
    val question: QuestionItemResponse,
    val answer: String?,
    val gradingResult: GradingResultResponse?,
    val topic: String,
    val difficulty: Int,
    val answeredAt: Instant?,
    val isPublic: Boolean,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val viewCount: Int = 0,
)

data class RecordsPageResponse(val records: List<StudyRecordResponse>, val totalCount: Long, val limit: Int, val offset: Int)

data class BackendSnapshotResponse(
    val settings: BackendSettingsResponse,
    val api: APIStatusResponse?,
    val records: List<StudyRecordResponse>,
    val stats: StatsResponse?,
    val totalCount: Long,
    val serverTime: Instant,
)
