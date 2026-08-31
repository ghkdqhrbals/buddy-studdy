package com.buddystudy.backend.study.application.model

import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import java.time.Instant

enum class StudyLearningRecordSource { QUESTION, VOICE_TUTOR }
enum class StudyLearningRecordScope { NODE, SUBTREE }

data class StudyLearningRecordKey(
    val source: StudyLearningRecordSource,
    val recordId: Long,
    val studyId: Long,
    val createdAt: Instant,
)

data class StudyLearningRecordsCursor(
    val createdAt: Instant,
    val source: StudyLearningRecordSource,
    val recordId: Long,
)

data class StudyLearningRecordResponse(
    val id: String,
    val source: StudyLearningRecordSource,
    val studyId: Long,
    val createdAt: Instant,
    val questionRecord: StudyRecordResponse? = null,
    val voiceRecord: VoiceStudyLearningRecordResponse? = null,
)

data class StudyLearningRecordsPageResponse(
    val items: List<StudyLearningRecordResponse>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val limit: Int,
)

data class VoiceStudyLearningRecordResponse(
    val id: String,
    val sessionId: String,
    val studyId: Long,
    val parentStudyId: Long?,
    val topic: String,
    val difficulty: Int,
    val createdAt: Instant,
    val kind: VoiceTutorExchangeKind,
    val question: String,
    val answer: String?,
    val score: Int?,
    val strengths: List<String>,
    val improvements: List<String>,
    val depthSummary: String,
    val feedback: String?,
    val questionTurnId: Long,
    val answerTurnIds: List<Long>,
    val feedbackTurnIds: List<Long>,
    val sourceLanguage: String,
    val requestedLanguage: String,
    val displayLanguage: String,
    val translationPending: Boolean,
)
