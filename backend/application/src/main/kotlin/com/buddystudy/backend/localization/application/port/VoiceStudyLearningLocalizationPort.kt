package com.buddystudy.backend.localization.application.port

import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.voice.domain.VoiceStudyLearningRecord
import java.time.Instant

interface VoiceStudyLearningLocalizationPort {
    suspend fun content(recordId: Long): VoiceStudyLearningRecord?
    suspend fun snapshot(recordId: Long, targetLanguage: String): TextLocalizationSnapshot?
    suspend fun request(record: VoiceStudyLearningRecord, targetLanguage: String, now: Instant)
    suspend fun saveReady(
        record: VoiceStudyLearningRecord,
        event: ContentTranslationRequestedEvent,
        result: ContentTranslationResult,
        now: Instant,
    ): Boolean
    suspend fun markFailed(event: ContentTranslationRequestedEvent, error: String, now: Instant)
}

object UnavailableVoiceStudyLearningLocalizationPort : VoiceStudyLearningLocalizationPort {
    override suspend fun content(recordId: Long): VoiceStudyLearningRecord? =
        error("Voice study learning localization is not configured.")
    override suspend fun snapshot(recordId: Long, targetLanguage: String): TextLocalizationSnapshot? = null
    override suspend fun request(record: VoiceStudyLearningRecord, targetLanguage: String, now: Instant) =
        error("Voice study learning localization is not configured.")
    override suspend fun saveReady(record: VoiceStudyLearningRecord, event: ContentTranslationRequestedEvent, result: ContentTranslationResult, now: Instant): Boolean = false
    override suspend fun markFailed(event: ContentTranslationRequestedEvent, error: String, now: Instant) = Unit
}
