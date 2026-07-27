package com.buddystudy.backend.study.application.model

import com.buddystudy.backend.common.application.model.PageResponse
import java.time.Instant

data class QuestionItemResponse(val question: String, val expectedAnswerHint: String? = null, val createdAt: Instant)
data class GradingCriterionResponse(
    val criterionId: String,
    val satisfied: Boolean,
    val evidence: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val reason: String = "",
)

data class GradingResultResponse(
    val score: Int,
    val isCorrect: Boolean,
    val feedback: String,
    val explanation: String,
    val verdict: String? = null,
    val confidence: Double? = null,
    val criteria: List<GradingCriterionResponse> = emptyList(),
    val contradictions: List<String> = emptyList(),
    val misconceptions: List<String> = emptyList(),
    val unsupportedClaims: List<String> = emptyList(),
    val auditReason: String? = null,
    val policyVersion: String? = null,
    val model: String? = null,
)

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
    val gradingRequestId: String? = null,
    val gradingStatus: AnswerGradingStatus? = null,
    val gradingError: String? = null,
    val localization: RecordLocalizationResponse? = null,
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
    val source: String = "CATALOG",
    val depth: Int = 1,
    val maxDepth: Int = 5,
    val childLimit: Int = 10,
)

data class StudyPageResponse(
    val studies: List<StudyRoomResponse>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
    val serverTime: Instant,
) : PageResponse
