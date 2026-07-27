package com.buddystudy.backend.test

import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.backend.localization.application.model.RecordSourceHashes
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.ContentLanguageDetectionPort
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.RequestContentLocalizationUseCase
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import java.time.Instant

class PassthroughLanguageDetector : ContentLanguageDetectionPort {
    override fun detect(text: String, fallbackLanguage: String): String =
        QuestionLanguage.normalize(fallbackLanguage)
}

class EmptyContentLocalizationPort : ContentLocalizationPort {
    override suspend fun record(questionId: Long, targetLanguage: String) =
        RecordLocalizationSnapshot(null, null, null)

    override suspend fun comment(commentId: Long, targetLanguage: String): TextLocalizationSnapshot? = null

    override suspend fun ensureRecordPending(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHashes: RecordSourceHashes,
        now: Instant,
    ) = true

    override suspend fun ensureCommentPending(
        comment: QuestionCommentEntity,
        targetLanguage: String,
        sourceHash: String,
        now: Instant,
    ) = true

    override suspend fun saveRecordReady(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHashes: RecordSourceHashes,
        result: ContentTranslationResult,
        now: Instant,
    ) = true

    override suspend fun saveCommentReady(
        comment: QuestionCommentEntity,
        targetLanguage: String,
        sourceHash: String,
        result: ContentTranslationResult,
        now: Instant,
    ) = true

    override suspend fun markFailed(event: ContentTranslationRequestedEvent, error: String, now: Instant) = Unit
}

class RecordingLocalizationRequests : RequestContentLocalizationUseCase {
    val records = mutableListOf<Pair<Long, String>>()
    val comments = mutableListOf<Pair<Long, String>>()

    override suspend fun requestRecord(question: QuestionEntity, targetLanguage: String) {
        records += question.id to targetLanguage
    }

    override suspend fun requestComment(comment: QuestionCommentEntity, targetLanguage: String) {
        comments += comment.id to targetLanguage
    }
}
