package com.buddystudy.backend.study.application.model

import com.buddystudy.voice.domain.VoiceTutorExchangeKind

/** Safe typed content shared by owned records and the existing public/comment record surface.
 * Session IDs, transcript/turn IDs, recording metadata and private tree IDs never belong here.
 */
data class VoiceRecordContentResponse(
    val kind: VoiceTutorExchangeKind,
    val score: Int?,
    val feedback: String?,
    val strengths: List<String>,
    val improvements: List<String>,
    val depthSummary: String,
    val sourceLanguage: String,
    val requestedLanguage: String,
    val displayLanguage: String,
    val translationPending: Boolean,
)

data class VoiceRecordProjection(
    val question: String,
    val answer: String?,
    val content: VoiceRecordContentResponse,
    val localization: RecordLocalizationResponse,
)
