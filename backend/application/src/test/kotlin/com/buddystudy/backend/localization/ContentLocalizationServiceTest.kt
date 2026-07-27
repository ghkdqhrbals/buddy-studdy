package com.buddystudy.backend.localization

import com.buddystudy.backend.common.application.outbox.OutboxPublishSummary
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.backend.localization.application.model.RecordSourceHashes
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.ContentTranslationEventPort
import com.buddystudy.backend.localization.application.service.ContentLocalizationService
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.entity.QuestionEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ContentLocalizationServiceTest {
    @Test
    fun `record event contains independent hashes and never includes source text`() = runBlocking {
        val localizations = RecordingLocalizationPort()
        val events = RecordingTranslationEvents()
        val publisher = RecordingPublisher()
        val service = ContentLocalizationService(localizations, events, publisher)
        val question = QuestionEntity(
            id = 42,
            topic = "Redis",
            question = "컨슈머 그룹을 설명하세요.",
            hint = "**PEL**을 포함하세요.",
            sourceLanguage = "ko",
            answer = "Consumer groups divide work.",
            answerSourceLanguage = "en",
            feedback = "핵심을 잘 설명했습니다.",
            explanation = "각 소비자는 서로 다른 메시지를 처리합니다.",
            aiResponseSourceLanguage = "ko",
        )

        service.requestRecord(question, "ja")

        val event = events.events.single()
        assertThat(event.targetLanguage).isEqualTo("ja")
        assertThat(event.questionSourceHash).isNotBlank()
        assertThat(event.answerSourceHash).isNotBlank()
        assertThat(event.aiResponseSourceHash).isNotBlank()
        assertThat(event.sourceHash).doesNotContain(question.question)
        assertThat(localizations.hashes?.question).isNotEqualTo(localizations.hashes?.answer)
        assertThat(publisher.references).hasSize(1)
    }

    @Test
    fun `changing only an answer preserves the question source hash`() {
        val original = QuestionEntity(id = 1, topic = "SQL", question = "인덱스를 설명하세요.", answer = "초안")
        val originalHashes = ContentLocalizationService.recordHashes(original)

        original.answer = "수정된 답변"
        val changed = ContentLocalizationService.recordHashes(original)

        assertThat(changed.question).isEqualTo(originalHashes.question)
        assertThat(changed.answer).isNotEqualTo(originalHashes.answer)
        assertThat(changed.record).isNotEqualTo(originalHashes.record)
    }
}

private class RecordingLocalizationPort : ContentLocalizationPort {
    var hashes: RecordSourceHashes? = null

    override suspend fun record(questionId: Long, targetLanguage: String) =
        RecordLocalizationSnapshot(null, null, null)

    override suspend fun comment(commentId: Long, targetLanguage: String): TextLocalizationSnapshot? = null

    override suspend fun ensureRecordPending(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHashes: RecordSourceHashes,
        now: Instant,
    ): Boolean {
        hashes = sourceHashes
        return true
    }

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

private class RecordingTranslationEvents : ContentTranslationEventPort {
    val events = mutableListOf<ContentTranslationRequestedEvent>()

    override suspend fun append(event: ContentTranslationRequestedEvent, now: Instant): Long {
        events += event
        return events.size.toLong()
    }
}

private class RecordingPublisher : PublishOutboxUseCase {
    val references = mutableListOf<OutboxReference>()

    override suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary {
        this.references += references
        return OutboxPublishSummary(references.size, references.size, 0)
    }
}
