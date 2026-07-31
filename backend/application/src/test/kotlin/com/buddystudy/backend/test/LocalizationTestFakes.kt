package com.buddystudy.backend.test

import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.model.PendingContentTranslation
import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.backend.localization.application.model.RecordSourceHashes
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.ContentLanguageDetectionPort
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.ContentTranslationEventPort
import com.buddystudy.backend.localization.application.port.RequestContentLocalizationUseCase
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import java.time.Instant

class PassthroughLanguageDetector : ContentLanguageDetectionPort {
    override fun detect(text: String, fallbackLanguage: String): String =
        QuestionLanguage.normalize(fallbackLanguage)
}

open class EmptyContentLocalizationPort : ContentLocalizationPort {
    override suspend fun record(questionId: Long, targetLanguage: String) =
        RecordLocalizationSnapshot(null, null, null)

    override suspend fun comment(commentId: Long, targetLanguage: String): TextLocalizationSnapshot? = null

    override suspend fun ensureRecordPending(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHashes: RecordSourceHashes,
        now: Instant,
        retryPendingBefore: Instant,
    ) = buildList {
        if (question.sourceLanguage.databaseValue != targetLanguage) {
            add(PendingContentTranslation(LocalizableContentType.QUESTION, sourceHashes.question, "request-question-$targetLanguage"))
        }
        val answerSource = question.answerSourceLanguage ?: question.sourceLanguage
        if (sourceHashes.answer != null && answerSource.databaseValue != targetLanguage) {
            add(PendingContentTranslation(LocalizableContentType.ANSWER, sourceHashes.answer, "request-answer-$targetLanguage"))
        }
        val aiSource = question.aiResponseSourceLanguage ?: question.sourceLanguage
        if (sourceHashes.aiResponse != null && aiSource.databaseValue != targetLanguage) {
            add(PendingContentTranslation(LocalizableContentType.AI_RESPONSE, sourceHashes.aiResponse, "request-ai-response-$targetLanguage"))
        }
    }

    override suspend fun ensureCommentPending(
        comment: QuestionCommentEntity,
        targetLanguage: String,
        sourceHash: String,
        now: Instant,
        retryPendingBefore: Instant,
    ) = if (comment.sourceLanguage.databaseValue == targetLanguage) {
        null
    } else {
        PendingContentTranslation(LocalizableContentType.COMMENT, sourceHash, "request-comment-$targetLanguage")
    }

    override suspend fun saveQuestionReady(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHash: String,
        result: ContentTranslationResult,
        now: Instant,
    ) = true

    override suspend fun saveAnswerReady(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHash: String,
        result: ContentTranslationResult,
        now: Instant,
    ) = true

    override suspend fun saveAiResponseReady(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHash: String,
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

class RecordingContentTranslationEventPort : ContentTranslationEventPort {
    val events = mutableListOf<ContentTranslationRequestedEvent>()

    override suspend fun append(event: ContentTranslationRequestedEvent, now: Instant): Long {
        events += event
        return events.size.toLong()
    }
}
