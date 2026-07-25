package com.buddystudy.backend.study.application.model

import com.buddystudy.backend.common.application.model.PageResponse
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
    val studyId: Long? = null,
)

data class RecordsPageResponse(
    val records: List<StudyRecordResponse>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
) : PageResponse

data class StudyRoomResponse(
    val id: Long,
    val parentStudyId: Long?,
    val sortOrder: Int,
    val topic: String,
    val difficultyLevel: Int,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val activeForQuestions: Boolean,
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

data class StudyTopicSuggestionsResponse(
    val parentStudyId: Long,
    val suggestions: List<String>,
)

data class StudyPageResponse(
    val studies: List<StudyRoomResponse>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
    val serverTime: Instant,
) : PageResponse
