package com.buddystudy.backend.study.application.model

import com.buddystudy.backend.common.application.model.PageResponse
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyRecordType
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
    val correlationId: String? = gradingRequestId,
    val gradingStatus: AnswerGradingStatus? = null,
    val gradingError: String? = null,
    val gradingLastEventId: Long? = null,
    val questionStatus: QuestionStatus = QuestionStatus.UNGRADED,
    val localization: RecordLocalizationResponse? = null,
    val recordType: StudyRecordType = StudyRecordType.QUESTION,
    val voiceRecord: VoiceRecordContentResponse? = null,
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
    val latestQuestion: StudyRecordResponse? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val curriculumTerminal: Boolean = false,
)

/**
 * Bounded result for create-only root-study operations. Existing root settings are deliberately
 * omitted so an AI caller cannot mistake a replay for permission to replace them.
 */
data class RootStudyCreationResponse(
    val created: Boolean,
    val id: Long,
    val parentStudyId: Long?,
    val topic: String,
    val difficultyLevel: Int,
    val enabled: Boolean,
    val activeForQuestions: Boolean,
    val curriculumTerminal: Boolean = false,
)

/** Owner-lock-protected outcome for idempotent child creation. */
data class StudyTopicCreationResponse(
    val created: Boolean,
    val id: Long,
    val parentStudyId: Long,
    val topic: String,
    val difficultyLevel: Int,
    val enabled: Boolean,
    val activeForQuestions: Boolean,
    val curriculumTerminal: Boolean = false,
)

data class StudyTopicSuggestionDetail(val topic: String, val curriculumTerminal: Boolean)

data class StudyTopicSuggestionsResponse(
    val parentStudyId: Long,
    val suggestions: List<String>,
    val source: String = "CATALOG",
    val depth: Int = 1,
    val maxDepth: Int = com.buddystudy.study.domain.StudyTreePolicy.MAX_DESCENDANT_DEPTH,
    val childLimit: Int = com.buddystudy.study.domain.StudyTreePolicy.MAX_TOPIC_SUGGESTIONS,
    val topicDetails: List<StudyTopicSuggestionDetail> = emptyList(),
)

data class StudyTopicsCreationResponse(
    val parentStudyId: Long,
    val topics: List<StudyTopicCreationResponse>,
    val maxDepth: Int = com.buddystudy.study.domain.StudyTreePolicy.MAX_DESCENDANT_DEPTH,
)

data class StudyPageResponse(
    val studies: List<StudyRoomResponse>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
    val serverTime: Instant,
) : PageResponse
