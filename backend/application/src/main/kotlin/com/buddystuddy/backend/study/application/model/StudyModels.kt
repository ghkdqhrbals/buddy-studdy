package com.buddystuddy.backend.study.application.model

import java.time.Instant

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

data class StudyRoomResponse(
    val id: Long,
    val topic: String,
    val difficultyLevel: Int,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val notificationSound: String?,
    val customPrompt: String,
    val openaiModel: String,
    val maxHistoryCount: Int,
    val nextDueAt: Instant?,
    val lastSentAt: Instant?,
    val lastError: String?,
    val pendingQuestion: StudyRecordResponse?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class StudyPageResponse(
    val studies: List<StudyRoomResponse>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
    val serverTime: Instant,
)
