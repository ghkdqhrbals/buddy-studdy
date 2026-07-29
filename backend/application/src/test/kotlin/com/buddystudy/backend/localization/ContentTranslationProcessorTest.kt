package com.buddystudy.backend.localization

import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.ContentTranslationPort
import com.buddystudy.backend.localization.application.service.ContentLocalizationService
import com.buddystudy.backend.localization.application.service.ContentTranslationProcessor
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.backend.test.EmptyContentLocalizationPort
import com.buddystudy.study.domain.entity.QuestionEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Duration
import java.time.Instant

class ContentTranslationProcessorTest {
    @Test
    fun `legacy record event is drained without translating bundled content`() = runBlocking {
        val translator = RecordingContentTranslator()
        val processor = ContentTranslationProcessor(
            questions = Mockito.mock(QuestionPort::class.java),
            comments = Mockito.mock(QuestionCommentPort::class.java),
            localizations = Mockito.mock(ContentLocalizationPort::class.java),
            translator = translator,
            inbox = RecordingStreamInbox(shouldClaim = true),
        )

        processor.process(
            ContentTranslationRequestedEvent(
                eventId = "content-translation-record-58-en-${"a".repeat(64)}",
                contentType = LocalizableContentType.RECORD,
                contentId = 58,
                targetLanguage = "en",
                sourceHash = "a".repeat(64),
                requestedAt = Instant.parse("2026-07-28T00:00:00Z"),
            ),
        )

        assertThat(translator.callCount).isZero()
        Unit
    }

    @Test
    fun `answer event translates and saves only the answer`() = runBlocking {
        val question = QuestionEntity(
            id = 58,
            topic = "MySQL",
            question = "인덱스를 설명하세요.",
            sourceLanguage = "ko",
            answer = "인덱스는 조회를 빠르게 하는 자료구조입니다.",
            answerSourceLanguage = "ko",
            feedback = "핵심을 잘 설명했습니다.",
            aiResponseSourceLanguage = "ko",
        )
        val answerHash = ContentLocalizationService.recordHashes(question).answer!!
        val questions = Mockito.mock(QuestionPort::class.java)
        Mockito.`when`(questions.findQuestionById(question.id)).thenReturn(question)
        val translator = RecordingContentTranslator()
        val savedTypes = mutableListOf<LocalizableContentType>()
        val localizations = object : EmptyContentLocalizationPort() {
            override suspend fun saveAnswerReady(
                question: QuestionEntity,
                targetLanguage: String,
                sourceHash: String,
                result: ContentTranslationResult,
                now: Instant,
            ): Boolean {
                savedTypes += LocalizableContentType.ANSWER
                return true
            }
        }
        val processor = ContentTranslationProcessor(
            questions = questions,
            comments = Mockito.mock(QuestionCommentPort::class.java),
            localizations = localizations,
            translator = translator,
            inbox = RecordingStreamInbox(shouldClaim = true),
        )

        processor.process(
            ContentTranslationRequestedEvent(
                eventId = "content-translation-answer-${question.id}-en-$answerHash",
                contentType = LocalizableContentType.ANSWER,
                contentId = question.id,
                targetLanguage = "en",
                sourceHash = answerHash,
                requestedAt = Instant.parse("2026-07-28T00:00:00Z"),
            ),
        )

        assertThat(translator.fields.keys).containsExactly("answer")
        assertThat(translator.sourceLanguages).containsExactlyEntriesOf(mapOf("answer" to "ko"))
        assertThat(savedTypes).containsExactly(LocalizableContentType.ANSWER)
        Unit
    }

    @Test
    fun `long idempotency event id uses a bounded stable inbox correlation id`() = runBlocking {
        val inbox = RecordingStreamInbox()
        val processor = ContentTranslationProcessor(
            questions = Mockito.mock(QuestionPort::class.java),
            comments = Mockito.mock(QuestionCommentPort::class.java),
            localizations = Mockito.mock(ContentLocalizationPort::class.java),
            translator = Mockito.mock(ContentTranslationPort::class.java),
            inbox = inbox,
        )
        val event = ContentTranslationRequestedEvent(
            eventId = "content-translation-answer-58-en-${"a".repeat(64)}",
            contentType = LocalizableContentType.ANSWER,
            contentId = 58,
            targetLanguage = "en",
            sourceHash = "a".repeat(64),
            requestedAt = Instant.parse("2026-07-28T00:00:00Z"),
        )

        processor.process(event)
        processor.process(event)

        assertThat(event.eventId.length).isGreaterThan(36)
        assertThat(inbox.correlationIds).hasSize(2)
        assertThat(inbox.correlationIds.distinct()).hasSize(1)
        val correlationId = inbox.correlationIds.distinct().single()
        assertThat(correlationId).hasSize(36)
        assertThat(correlationId).isNotEqualTo(event.eventId)
        Unit
    }

    @Test
    fun `retryable translation failure records the failed attempt before redis redelivery`() = runBlocking {
        val question = question()
        val questions = Mockito.mock(QuestionPort::class.java)
        Mockito.`when`(questions.findQuestionById(question.id)).thenReturn(question)
        val inbox = RecordingStreamInbox(shouldClaim = true, attempt = 1)
        val processor = ContentTranslationProcessor(
            questions = questions,
            comments = Mockito.mock(QuestionCommentPort::class.java),
            localizations = EmptyContentLocalizationPort(),
            translator = FailingContentTranslator(),
            inbox = inbox,
        )

        val failure = runCatching {
            processor.process(questionEvent(question))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            .hasMessage("translation provider unavailable")
        assertThat(inbox.retryFailures).containsExactly(
            IllegalStateException::class.java.name to "translation provider unavailable",
        )
        assertThat(inbox.terminalFailures).isEmpty()
        Unit
    }

    @Test
    fun `third translation failure is persisted as a terminal inbox failure`() = runBlocking {
        val question = question()
        val questions = Mockito.mock(QuestionPort::class.java)
        Mockito.`when`(questions.findQuestionById(question.id)).thenReturn(question)
        val inbox = RecordingStreamInbox(shouldClaim = true, attempt = 3)
        var localizationFailure: String? = null
        val localizations = object : EmptyContentLocalizationPort() {
            override suspend fun markFailed(
                event: ContentTranslationRequestedEvent,
                error: String,
                now: Instant,
            ) {
                localizationFailure = error
            }
        }
        val processor = ContentTranslationProcessor(
            questions = questions,
            comments = Mockito.mock(QuestionCommentPort::class.java),
            localizations = localizations,
            translator = FailingContentTranslator(),
            inbox = inbox,
        )

        processor.process(questionEvent(question))

        assertThat(localizationFailure).isEqualTo("translation provider unavailable")
        assertThat(inbox.retryFailures).isEmpty()
        assertThat(inbox.terminalFailures).containsExactly(
            IllegalStateException::class.java.name to "translation provider unavailable",
        )
        Unit
    }

    private fun question() = QuestionEntity(
        id = 58,
        topic = "MySQL",
        question = "인덱스를 설명하세요.",
        sourceLanguage = "ko",
    )

    private fun questionEvent(question: QuestionEntity) = ContentTranslationRequestedEvent(
        eventId = "content-translation-question-${question.id}-en-${ContentLocalizationService.recordHashes(question).question}",
        contentType = LocalizableContentType.QUESTION,
        contentId = question.id,
        targetLanguage = "en",
        sourceHash = ContentLocalizationService.recordHashes(question).question,
        requestedAt = Instant.parse("2026-07-28T00:00:00Z"),
    )
}

private class RecordingStreamInbox(
    private val shouldClaim: Boolean = false,
    private val attempt: Int = 1,
) : StreamInboxPort {
    val correlationIds = mutableListOf<String>()
    val retryFailures = mutableListOf<Pair<String, String>>()
    val terminalFailures = mutableListOf<Pair<String, String>>()

    override suspend fun claim(
        eventId: String,
        consumerGroup: String,
        correlationId: String,
        leaseDuration: Duration,
        now: Instant,
        streamKey: String,
    ): StreamInboxClaim? {
        correlationIds += correlationId
        return if (shouldClaim) {
            StreamInboxClaim(eventId, consumerGroup, "claim-token", attempt, streamKey)
        } else {
            null
        }
    }

    override suspend fun markSucceeded(claim: StreamInboxClaim, now: Instant) = true

    override suspend fun releaseForRetry(
        claim: StreamInboxClaim,
        errorType: String,
        errorMessage: String,
        now: Instant,
    ): Boolean {
        retryFailures += errorType to errorMessage
        return true
    }

    override suspend fun markFailed(
        claim: StreamInboxClaim,
        errorType: String,
        errorMessage: String,
        now: Instant,
    ): Boolean {
        terminalFailures += errorType to errorMessage
        return true
    }
}

private class RecordingContentTranslator : ContentTranslationPort {
    var fields: Map<String, String?> = emptyMap()
    var sourceLanguages: Map<String, String> = emptyMap()
    var callCount: Int = 0

    override suspend fun translate(
        fields: Map<String, String?>,
        sourceLanguages: Map<String, String>,
        targetLanguage: String,
    ): ContentTranslationResult {
        callCount += 1
        this.fields = fields
        this.sourceLanguages = sourceLanguages
        return ContentTranslationResult(
            fields = fields.mapValues { (_, value) -> value?.let { "translated:$it" } },
            provider = "test",
        )
    }
}

private class FailingContentTranslator : ContentTranslationPort {
    override suspend fun translate(
        fields: Map<String, String?>,
        sourceLanguages: Map<String, String>,
        targetLanguage: String,
    ): ContentTranslationResult {
        throw IllegalStateException("translation provider unavailable")
    }
}
