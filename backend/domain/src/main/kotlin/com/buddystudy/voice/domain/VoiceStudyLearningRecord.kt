package com.buddystudy.voice.domain

import java.time.Instant

/** Source-backed voice details; [recordId] is the shared question/record identity. */
data class VoiceStudyLearningRecord(
    val id: Long,
    val userId: Long,
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
    val sourceLanguages: Map<String, String>,
    val sourceHash: String,
    /** [id] stays the internal/legacy extension key for existing translation requests. */
    val recordId: Long? = null,
) {
    /** Only these text fields may be translated; identity, hierarchy and assessment never are. */
    fun translatableFields(): Map<String, String?> = linkedMapOf<String, String?>(
        "question" to question,
        "answer" to answer,
        "feedback" to feedback,
        "depthSummary" to depthSummary,
    ).apply {
        strengths.forEachIndexed { index, text -> put("strength$index", text) }
        improvements.forEachIndexed { index, text -> put("improvement$index", text) }
    }.filterValues { !it.isNullOrBlank() }
}
