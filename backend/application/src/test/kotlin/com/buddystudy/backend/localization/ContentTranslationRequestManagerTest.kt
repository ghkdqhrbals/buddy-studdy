package com.buddystudy.backend.localization

import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.model.PendingContentTranslation
import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.backend.localization.application.model.RecordSourceHashes
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.ContentTranslationEventPort
import com.buddystudy.backend.localization.application.service.ContentTranslationRequestManager
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ContentTranslationRequestManagerTest {
    private val localizations = LanguageAwareLocalizationPort()
    private val events = RecordingTranslationEventPort()
    private val manager = ContentTranslationRequestManager(localizations, events)

    @Test
    fun `record mutation appends one request for every missing supported translation`() = runBlocking<Unit> {
        val now = Instant.parse("2026-07-31T00:00:00Z")
        val question = QuestionEntity(
            id = 42,
            topic = "Redis",
            question = "컨슈머 그룹을 설명하세요.",
            sourceLanguage = SupportedLanguage.KOREAN,
            answer = "Consumer groups divide work.",
            answerSourceLanguage = SupportedLanguage.ENGLISH,
            feedback = "よく説明できました。",
            explanation = "各 consumer が作業を分担します。",
            aiResponseSourceLanguage = SupportedLanguage.JAPANESE,
        )

        val references = manager.appendRecordForSupportedLanguages(question, now)

        assertThat(events.rows.map { it.contentType to it.targetLanguage }).containsExactlyInAnyOrder(
            LocalizableContentType.QUESTION to QuestionLanguage.ENGLISH,
            LocalizableContentType.QUESTION to QuestionLanguage.JAPANESE,
            LocalizableContentType.ANSWER to QuestionLanguage.KOREAN,
            LocalizableContentType.ANSWER to QuestionLanguage.JAPANESE,
            LocalizableContentType.AI_RESPONSE to QuestionLanguage.KOREAN,
            LocalizableContentType.AI_RESPONSE to QuestionLanguage.ENGLISH,
        )
        assertThat(events.rows).allMatch { it.requestedAt == now }
        assertThat(references.size).isEqualTo(events.rows.size)
    }

    @Test
    fun `comment mutation appends requests for every language except its source`() = runBlocking<Unit> {
        val now = Instant.parse("2026-07-31T00:00:00Z")
        val comment = QuestionCommentEntity(
            id = 7,
            questionId = 42,
            userId = 3,
            body = "This needs a translation.",
            sourceLanguage = SupportedLanguage.ENGLISH,
        )

        val references = manager.appendCommentForSupportedLanguages(comment, now)

        assertThat(events.rows.map(ContentTranslationRequestedEvent::targetLanguage))
            .containsExactlyInAnyOrder(QuestionLanguage.KOREAN, QuestionLanguage.JAPANESE)
        assertThat(events.rows).allMatch { it.contentType == LocalizableContentType.COMMENT }
        assertThat(references.size).isEqualTo(2)
    }
}

private class LanguageAwareLocalizationPort : ContentLocalizationPort {
    override suspend fun record(questionId: Long, targetLanguage: String) =
        RecordLocalizationSnapshot(null, null, null)

    override suspend fun comment(commentId: Long, targetLanguage: String): TextLocalizationSnapshot? = null

    override suspend fun ensureRecordPending(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHashes: RecordSourceHashes,
        now: Instant,
        retryPendingBefore: Instant,
    ): List<PendingContentTranslation> = buildList {
        if (question.sourceLanguage.databaseValue != targetLanguage) {
            add(PendingContentTranslation(LocalizableContentType.QUESTION, sourceHashes.question, "question-$targetLanguage"))
        }
        val answerSource = question.answerSourceLanguage ?: question.sourceLanguage
        if (sourceHashes.answer != null && answerSource.databaseValue != targetLanguage) {
            add(PendingContentTranslation(LocalizableContentType.ANSWER, sourceHashes.answer, "answer-$targetLanguage"))
        }
        val aiSource = question.aiResponseSourceLanguage ?: question.sourceLanguage
        if (sourceHashes.aiResponse != null && aiSource.databaseValue != targetLanguage) {
            add(PendingContentTranslation(LocalizableContentType.AI_RESPONSE, sourceHashes.aiResponse, "ai-$targetLanguage"))
        }
    }

    override suspend fun ensureCommentPending(
        comment: QuestionCommentEntity,
        targetLanguage: String,
        sourceHash: String,
        now: Instant,
        retryPendingBefore: Instant,
    ): PendingContentTranslation? = if (comment.sourceLanguage.databaseValue == targetLanguage) {
        null
    } else {
        PendingContentTranslation(LocalizableContentType.COMMENT, sourceHash, "comment-$targetLanguage")
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

private class RecordingTranslationEventPort : ContentTranslationEventPort {
    val rows = mutableListOf<ContentTranslationRequestedEvent>()

    override suspend fun append(event: ContentTranslationRequestedEvent, now: Instant): Long {
        rows += event
        return rows.size.toLong()
    }
}
