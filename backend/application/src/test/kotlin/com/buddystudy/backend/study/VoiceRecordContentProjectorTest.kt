package com.buddystudy.backend.study

import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.VoiceStudyLearningLocalizationPort
import com.buddystudy.backend.study.application.model.TranslationViewMode
import com.buddystudy.backend.study.application.service.VoiceRecordContentProjector
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyRecordType
import com.buddystudy.voice.domain.VoiceStudyLearningRecord
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.time.Instant

class VoiceRecordContentProjectorTest {
    private val localizations = CommonRecordVoiceLocalizations()
    private val projector = VoiceRecordContentProjector(localizations)

    @Test
    fun `Spring instantiates the projector with its real localization port rather than a fallback constructor`(): Unit = runBlocking {
        val voice = commonRecordVoiceFixture()
        localizations.records[voice.id] = voice
        AnnotationConfigApplicationContext().use { context ->
            context.beanFactory.registerSingleton("voiceLocalizations", localizations)
            context.register(VoiceRecordContentProjector::class.java)
            context.refresh()
            val bean = context.getBean(VoiceRecordContentProjector::class.java)
            val result = bean.project(commonRecordVoiceQuestion(), "ko", TranslationViewMode.ORIGINAL)
            assertThat(result?.question).isEqualTo(voice.question)
            assertThat(localizations.reads).isEqualTo(1)
        }
    }

    @Test
    fun `ordinary records never query or create voice translation work`(): Unit = runBlocking {
        assertThat(projector.project(QuestionEntity(id = 9), "en", TranslationViewMode.LOCALIZED)).isNull()
        assertThat(localizations.reads).isZero()
        assertThat(localizations.requests).isEmpty()
    }

    @Test
    fun `common voice content carries real optional assessment without session or tree evidence`(): Unit = runBlocking {
        val voice = commonRecordVoiceFixture(score = null)
        localizations.records[voice.id] = voice
        val result = projector.project(commonRecordVoiceQuestion(), "ko", TranslationViewMode.ORIGINAL)!!
        assertThat(result.question).isEqualTo(voice.question)
        assertThat(result.answer).isEqualTo(voice.answer)
        assertThat(result.content.kind).isEqualTo(VoiceTutorExchangeKind.LEARNER_QUESTION)
        assertThat(result.content.score).isNull()
        assertThat(result.content.feedback).isEqualTo(voice.feedback)
        val json = JsonMapperProvider.mapper.writeValueAsString(result.content)
        assertThat(json).doesNotContain("sessionId", "questionTurnId", "answerTurnIds", "studyId", "private-session", "recording")
        assertThat(localizations.requests).isEmpty()
    }

    @Test
    fun `completed matching translation supplies all common text and metadata`(): Unit = runBlocking {
        val voice = commonRecordVoiceFixture()
        localizations.records[voice.id] = voice
        localizations.translated = TextLocalizationSnapshot("ko", "en", voice.sourceHash, "READY",
            voice.translatableFields().mapValues { "English ${it.key}" }, "test")
        val result = projector.project(commonRecordVoiceQuestion(), "en", TranslationViewMode.LOCALIZED)!!
        assertThat(result.question).isEqualTo("English question")
        assertThat(result.answer).isEqualTo("English answer")
        assertThat(result.content.feedback).isEqualTo("English feedback")
        assertThat(result.content.strengths).containsExactly("English strength0")
        assertThat(result.content.improvements).containsExactly("English improvement0")
        assertThat(result.content.depthSummary).isEqualTo("English depthSummary")
        assertThat(result.content.score).isEqualTo(83)
        assertThat(result.content.displayLanguage).isEqualTo("en")
        assertThat(result.content.translationPending).isFalse()
        assertThat(localizations.requests).isEmpty()
    }

    @Test
    fun `stale incomplete and failed translations preserve originals with bounded read repair`(): Unit = runBlocking {
        val voice = commonRecordVoiceFixture()
        localizations.records[voice.id] = voice
        val invalid = listOf(
            TextLocalizationSnapshot("ko", "en", "old-hash", "READY", voice.translatableFields(), "test"),
            TextLocalizationSnapshot("ko", "en", voice.sourceHash, "READY", mapOf("question" to "partial"), "test"),
            TextLocalizationSnapshot("ko", "en", voice.sourceHash, "FAILED", emptyMap(), "test"),
        )
        for (snapshot in invalid) {
            localizations.translated = snapshot
            val result = projector.project(commonRecordVoiceQuestion(), "en", TranslationViewMode.LOCALIZED)!!
            assertThat(result.question).isEqualTo(voice.question)
            assertThat(result.content.displayLanguage).isEqualTo("ko")
            assertThat(result.content.translationPending).isEqualTo(snapshot.status != "FAILED")
        }
        assertThat(localizations.requests).containsExactly(7L to "en", 7L to "en", 7L to "en")
    }

    @Test
    fun `a missing deleted foreign or mismatched typed extension cannot expose stale source content`(): Unit = runBlocking {
        val q = commonRecordVoiceQuestion()
        val voice = commonRecordVoiceFixture()
        for (invalid in listOf(null, voice.copy(userId = 99), voice.copy(recordId = 901), voice.copy(recordId = null))) {
            localizations.records.clear()
            invalid?.let { localizations.records[it.id] = it }
            assertThat(runCatching { projector.project(q, "ko", TranslationViewMode.ORIGINAL) }.exceptionOrNull())
                .isInstanceOf(ApiException::class.java)
        }
        assertThat(localizations.requests).isEmpty()
    }

    @Test
    fun `translation failure preserves source and cancellation is not swallowed`(): Unit = runBlocking {
        localizations.records[7] = commonRecordVoiceFixture()
        localizations.snapshotFailure = IllegalStateException("private provider text")
        assertThat(projector.project(commonRecordVoiceQuestion(), "en", TranslationViewMode.LOCALIZED)!!.question)
            .isEqualTo(commonRecordVoiceFixture().question)
        localizations.snapshotFailure = CancellationException("cancelled")
        assertThat(runCatching { projector.project(commonRecordVoiceQuestion(), "en", TranslationViewMode.LOCALIZED) }.exceptionOrNull())
            .isInstanceOf(CancellationException::class.java)
    }
}

internal class CommonRecordVoiceLocalizations : VoiceStudyLearningLocalizationPort {
    val records = mutableMapOf<Long, VoiceStudyLearningRecord>()
    val requests = mutableListOf<Pair<Long, String>>()
    var reads = 0
    var translated: TextLocalizationSnapshot? = null
    var snapshotFailure: Exception? = null
    override suspend fun content(recordId: Long): VoiceStudyLearningRecord? { reads++; return records[recordId] }
    override suspend fun snapshot(recordId: Long, targetLanguage: String): TextLocalizationSnapshot? {
        snapshotFailure?.let { throw it }; return translated
    }
    override suspend fun request(record: VoiceStudyLearningRecord, targetLanguage: String, now: Instant) {
        requests += record.id to targetLanguage
    }
    override suspend fun saveReady(record: VoiceStudyLearningRecord, event: ContentTranslationRequestedEvent, result: ContentTranslationResult, now: Instant) = false
    override suspend fun markFailed(event: ContentTranslationRequestedEvent, error: String, now: Instant) = Unit
}

internal fun commonRecordVoiceQuestion() = QuestionEntity(
    id = 900, userId = 7, studyId = 11, question = "Redis 캐시가 뭔가요?", answer = "빠른 재사용을 위한 임시 저장입니다.",
    topic = "Redis", difficultyLevel = 3, recordType = StudyRecordType.VOICE_TUTOR, voiceRecordId = 7,
    status = QuestionStatus.COMPLETED, score = 83, publicQuestion = false,
)

internal fun commonRecordVoiceFixture(score: Int? = 83) = VoiceStudyLearningRecord(
    id = 7, recordId = 900, userId = 7, sessionId = "private-session", studyId = 11, parentStudyId = 1,
    topic = "Redis", difficulty = 3, createdAt = Instant.parse("2026-08-31T00:00:00Z"),
    kind = if (score == null) VoiceTutorExchangeKind.LEARNER_QUESTION else VoiceTutorExchangeKind.TUTOR_QUESTION,
    question = "Redis 캐시가 뭔가요?", answer = "빠른 재사용을 위한 임시 저장입니다.",
    score = score, strengths = listOf("용도를 설명함"), improvements = listOf("만료 정책 확인"), depthSummary = "캐시의 용도를 탐구함",
    feedback = "기본 개념을 확인했어요.", questionTurnId = 123, answerTurnIds = listOf(124), feedbackTurnIds = listOf(125),
    sourceLanguage = "ko", sourceLanguages = mapOf("question" to "ko", "answer" to "ko", "feedback" to "ko", "depthSummary" to "ko", "strength0" to "ko", "improvement0" to "ko"),
    sourceHash = "stable-source-hash",
)
