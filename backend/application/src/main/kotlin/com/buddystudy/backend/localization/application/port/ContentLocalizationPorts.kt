package com.buddystudy.backend.localization.application.port

import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.backend.localization.application.model.RecordSourceHashes
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import java.time.Instant

interface ContentLocalizationPort {
    suspend fun record(questionId: Long, targetLanguage: String): RecordLocalizationSnapshot
    suspend fun comment(commentId: Long, targetLanguage: String): TextLocalizationSnapshot?
    suspend fun ensureRecordPending(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHashes: RecordSourceHashes,
        now: Instant,
    ): Boolean
    suspend fun ensureCommentPending(
        comment: QuestionCommentEntity,
        targetLanguage: String,
        sourceHash: String,
        now: Instant,
    ): Boolean
    suspend fun saveRecordReady(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHashes: RecordSourceHashes,
        result: ContentTranslationResult,
        now: Instant,
    ): Boolean
    suspend fun saveCommentReady(
        comment: QuestionCommentEntity,
        targetLanguage: String,
        sourceHash: String,
        result: ContentTranslationResult,
        now: Instant,
    ): Boolean
    suspend fun markFailed(
        event: ContentTranslationRequestedEvent,
        error: String,
        now: Instant,
    )
}

interface ContentTranslationPort {
    suspend fun translate(
        fields: Map<String, String?>,
        sourceLanguages: Map<String, String>,
        targetLanguage: String,
    ): ContentTranslationResult
}

interface ContentTranslationEventPort {
    suspend fun append(event: ContentTranslationRequestedEvent, now: Instant = Instant.now()): Long
}
