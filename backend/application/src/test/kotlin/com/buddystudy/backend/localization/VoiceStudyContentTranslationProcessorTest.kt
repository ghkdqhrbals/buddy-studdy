package com.buddystudy.backend.localization

import com.buddystudy.backend.common.application.privacy.PrivateLearningContentLogScope
import com.buddystudy.backend.common.application.stream.StreamRetryScheduledException
import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.ContentTranslationPort
import com.buddystudy.backend.localization.application.port.VoiceStudyLearningLocalizationPort
import com.buddystudy.backend.localization.application.service.ContentTranslationProcessor
import com.buddystudy.backend.study.application.model.StreamInboxClaim
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import com.buddystudy.voice.domain.VoiceStudyLearningRecord
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Duration
import java.time.Instant

class VoiceStudyContentTranslationProcessorTest {
    @Test
    fun `voice work uses existing inbox and translates only source text in private context`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.processor.process(fixture.event)
        assertThat(fixture.translatedFields).isEqualTo(fixture.record.translatableFields())
        assertThat(fixture.translatedFields.keys).doesNotContain("studyId", "questionTurnId", "score", "difficulty", "sessionId", "topic")
        assertThat(fixture.privateDuringTranslation).isTrue()
        assertThat(PrivateLearningContentLogScope.isActive()).isFalse()
        assertThat(fixture.saved).hasSize(1)
        assertThat(fixture.record.answer).isEqualTo("private learner answer")
        assertThat(fixture.record.score).isEqualTo(85)
        assertThat(fixture.succeeded).isEqualTo(1)
        Mockito.verifyNoInteractions(fixture.questions, fixture.comments, fixture.regularLocalizations)
    }

    @Test
    fun `deleted voice record drains event without translating or creating question data`() = runBlocking<Unit> {
        val fixture = Fixture().apply { found = null }
        fixture.processor.process(fixture.event)
        assertThat(fixture.translatedFields).isEmpty()
        assertThat(fixture.saved).isEmpty()
        assertThat(fixture.succeeded).isEqualTo(1)
    }

    @Test
    fun `stale source hash is drained without translating newer private content`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.processor.process(fixture.event.copy(sourceHash = "stale-hash"))
        assertThat(fixture.translatedFields).isEmpty()
        assertThat(fixture.saved).isEmpty()
        assertThat(fixture.succeeded).isEqualTo(1)
    }

    @Test
    fun `voice retry cause and inbox metadata cannot leak a provider response containing the learner answer`() = runBlocking<Unit> {
        val fixture = Fixture().apply { failure = IllegalStateException("private learner answer returned by provider") }
        val error = runCatching { fixture.processor.process(fixture.event) }.exceptionOrNull()
        assertThat(error).isInstanceOf(StreamRetryScheduledException::class.java)
        assertThat(error!!.stackTraceToString()).doesNotContain("private learner answer")
        assertThat(fixture.retryFailures.single()).isEqualTo("Voice learning record translation failed.")
        assertThat(fixture.failedLocalizations).isEmpty()
        assertThat(fixture.succeeded).isZero()
        assertThat(PrivateLearningContentLogScope.isActive()).isFalse()
    }

    @Test
    fun `third voice failure marks only matching voice localization and never ordinary translations`() = runBlocking<Unit> {
        val fixture = Fixture(attempt = 3).apply { failure = IllegalStateException("private translated text") }
        fixture.processor.process(fixture.event)
        assertThat(fixture.failedLocalizations).containsExactly(fixture.event)
        assertThat(fixture.terminalFailures).containsExactly("Voice learning record translation failed.")
        assertThat(fixture.retryFailures).isEmpty()
        Mockito.verifyNoInteractions(fixture.regularLocalizations)
    }

    @Test
    fun `cancellation keeps recoverable inbox lease and does not mark voice summary failed`() = runBlocking<Unit> {
        val fixture = Fixture().apply { failure = CancellationException("shutdown") }
        assertThat(runCatching { fixture.processor.process(fixture.event) }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(fixture.retryFailures).isEmpty()
        assertThat(fixture.terminalFailures).isEmpty()
        assertThat(fixture.failedLocalizations).isEmpty()
        assertThat(fixture.succeeded).isZero()
        assertThat(PrivateLearningContentLogScope.isActive()).isFalse()
    }

    @Test
    fun `inbox duplicate is rejected before private content lookup`() = runBlocking<Unit> {
        val fixture = Fixture(claimable = false)
        fixture.processor.process(fixture.event)
        assertThat(fixture.lookups).isZero()
        assertThat(fixture.translatedFields).isEmpty()
        assertThat(fixture.succeeded).isZero()
    }

    @Test
    fun `a stale request token losing save CAS is drained without overriding the pending successor`() = runBlocking<Unit> {
        val fixture = Fixture().apply { saveAccepted = false }
        fixture.processor.process(fixture.event)
        assertThat(fixture.saved).hasSize(1)
        assertThat(fixture.succeeded).isEqualTo(1)
        assertThat(fixture.retryFailures).isEmpty()
    }

    private class Fixture(private val attempt: Int = 1, private val claimable: Boolean = true) {
        val now = Instant.parse("2026-08-31T00:00:00Z")
        val record = VoiceStudyLearningRecord(
            id = 51, userId = 7, sessionId = "private-session", studyId = 12, parentStudyId = 2,
            topic = "Redis", difficulty = 4, createdAt = now, kind = VoiceTutorExchangeKind.TUTOR_QUESTION,
            question = "private tutor question", answer = "private learner answer", score = 85,
            strengths = listOf("private strength"), improvements = listOf("private improvement"),
            depthSummary = "private depth", feedback = "private feedback 85점",
            questionTurnId = 1, answerTurnIds = listOf(2), feedbackTurnIds = listOf(3),
            sourceLanguage = "ko", sourceLanguages = mapOf("question" to "ko", "answer" to "en"), sourceHash = "a".repeat(64),
        )
        val event = ContentTranslationRequestedEvent("content-translation-test-token", LocalizableContentType.VOICE_STUDY_RECORD, 51, "ja", record.sourceHash, requestedAt = now)
        var found: VoiceStudyLearningRecord? = record
        var failure: Exception? = null
        var privateDuringTranslation = false
        var translatedFields = emptyMap<String, String?>()
        var lookups = 0
        var succeeded = 0
        var saveAccepted = true
        val saved = mutableListOf<ContentTranslationResult>()
        val failedLocalizations = mutableListOf<ContentTranslationRequestedEvent>()
        val retryFailures = mutableListOf<String>()
        val terminalFailures = mutableListOf<String>()
        val questions = Mockito.mock(QuestionPort::class.java)
        val comments = Mockito.mock(QuestionCommentPort::class.java)
        val regularLocalizations = Mockito.mock(ContentLocalizationPort::class.java)
        val processor = ContentTranslationProcessor(
            questions, comments, regularLocalizations,
            object : ContentTranslationPort {
                override suspend fun translate(fields: Map<String, String?>, sourceLanguages: Map<String, String>, targetLanguage: String): ContentTranslationResult {
                    privateDuringTranslation = PrivateLearningContentLogScope.isActive()
                    translatedFields = fields
                    assertThat(sourceLanguages).isEqualTo(record.sourceLanguages)
                    assertThat(targetLanguage).isEqualTo("ja")
                    failure?.let { throw it }
                    return ContentTranslationResult(fields.mapValues { "translated:${it.value}" }, "synthetic")
                }
            },
            object : StreamInboxPort {
                override suspend fun claim(eventId: String, consumerGroup: String, correlationId: String, leaseDuration: Duration, now: Instant, streamKey: String): StreamInboxClaim? =
                    if (claimable) StreamInboxClaim(eventId, consumerGroup, "claim", attempt, streamKey) else null
                override suspend fun markSucceeded(claim: StreamInboxClaim, now: Instant): Boolean { succeeded++; return true }
                override suspend fun releaseForRetry(claim: StreamInboxClaim, errorType: String, errorMessage: String, now: Instant): Boolean { retryFailures += errorMessage; return true }
                override suspend fun markFailed(claim: StreamInboxClaim, errorType: String, errorMessage: String, now: Instant): Boolean { terminalFailures += errorMessage; return true }
            },
            object : VoiceStudyLearningLocalizationPort {
                override suspend fun content(recordId: Long): VoiceStudyLearningRecord? { lookups++; return found }
                override suspend fun snapshot(recordId: Long, targetLanguage: String): TextLocalizationSnapshot? = error("processor must not project UI")
                override suspend fun request(record: VoiceStudyLearningRecord, targetLanguage: String, now: Instant) = error("processor must not enqueue a successor")
                override suspend fun saveReady(record: VoiceStudyLearningRecord, event: ContentTranslationRequestedEvent, result: ContentTranslationResult, now: Instant): Boolean {
                    saved += result
                    return saveAccepted
                }
                override suspend fun markFailed(event: ContentTranslationRequestedEvent, error: String, now: Instant) { failedLocalizations += event }
            },
        )
    }
}
