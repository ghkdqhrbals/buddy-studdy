package com.buddystudy.backend.localization.application.model

import java.time.Instant

enum class LocalizableContentType {
    /**
     * Kept only so translation requests already stored in the outbox/stream can
     * still be drained after question, answer, and AI response were separated.
     */
    RECORD,
    QUESTION,
    ANSWER,
    AI_RESPONSE,
    COMMENT,
}

data class ContentTranslationRequestedEvent(
    val eventId: String,
    val contentType: LocalizableContentType,
    val contentId: Long,
    val targetLanguage: String,
    val sourceHash: String,
    val questionSourceHash: String? = null,
    val answerSourceHash: String? = null,
    val aiResponseSourceHash: String? = null,
    val requestedAt: Instant,
    val eventType: String = EVENT_TYPE,
    val eventVersion: Int = 1,
) {
    companion object {
        const val EVENT_TYPE = "CONTENT_TRANSLATION_REQUESTED"
    }
}

data class PendingContentTranslation(
    val contentType: LocalizableContentType,
    val sourceHash: String,
    val requestToken: String,
)

data class TextLocalizationSnapshot(
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceHash: String,
    val status: String,
    val fields: Map<String, String?>,
    val provider: String? = null,
)

data class RecordSourceHashes(
    val record: String,
    val question: String,
    val answer: String?,
    val aiResponse: String?,
)

data class RecordLocalizationSnapshot(
    val question: TextLocalizationSnapshot?,
    val answer: TextLocalizationSnapshot?,
    val aiResponse: TextLocalizationSnapshot?,
)

data class ContentTranslationResult(
    val fields: Map<String, String?>,
    val provider: String,
)
