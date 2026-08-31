package com.buddystudy.backend.study

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.VoiceStudyLearningLocalizationPort
import com.buddystudy.backend.study.application.model.GradingResultResponse
import com.buddystudy.backend.study.application.model.QuestionItemResponse
import com.buddystudy.backend.study.application.model.StudyLearningRecordKey
import com.buddystudy.backend.study.application.model.StudyLearningRecordScope
import com.buddystudy.backend.study.application.model.StudyLearningRecordSource
import com.buddystudy.backend.study.application.model.StudyLearningRecordsCursor
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.VoiceStudyLearningRecordResponse
import com.buddystudy.backend.study.application.policy.StudyLearningRecordsCursorPolicy
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.outbound.StudyLearningRecordQueryPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.port.outbound.VoiceStudyLearningRecordQueryPort
import com.buddystudy.backend.study.application.service.StudyLearningRecordService
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyEntity
import com.buddystudy.voice.domain.VoiceStudyLearningRecord
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.http.HttpStatus
import java.time.Instant

/** Private synthetic history only. Any call outside the allowed read ports fails the fixture. */
@Timeout(15)
class StudyLearningRecordServiceTest {
    @Test
    fun `missing or other-owner study is not found before any history is read`(): Unit = runBlocking {
        for (study in listOf(null, StudyEntity(id = NODE, userId = 99, topic = "Same title"))) {
            val fixture = Fixture().apply { studies.row = study }

            expectApiFailure(ApiErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND) { fixture.page() }

            assertThat(fixture.studies.reads).containsExactly(NODE to USER)
            assertThat(fixture.keys.calls).isEmpty()
            assertThat(fixture.questions.calls).isEmpty()
            assertThat(fixture.voices.batchReads).isEmpty()
            assertThat(fixture.localizations.reads).isEmpty()
        }
    }

    @Test
    fun `invalid scope or view cannot fall back to a broader history query`(): Unit = runBlocking {
        val fixture = Fixture()
        for (scope in listOf("", "all", "NODE", "subtree ")) {
            expectApiFailure(ApiErrorCode.VALIDATION_ERROR) { fixture.page(scope = scope) }
        }
        for (view in listOf("", "all", "Original")) {
            expectApiFailure(ApiErrorCode.VALIDATION_ERROR) { fixture.page(view = view) }
        }

        assertThat(fixture.keys.calls).isEmpty()
        assertThat(fixture.localizations.reads).isEmpty()
    }

    @Test
    fun `cursor from another owner node or scope is rejected before key lookup`(): Unit = runBlocking {
        val fixture = Fixture()
        val position = key(StudyLearningRecordSource.VOICE_TUTOR, 99)
        val invalid = listOf(
            StudyLearningRecordsCursorPolicy.encode(8, NODE, StudyLearningRecordScope.NODE, position),
            StudyLearningRecordsCursorPolicy.encode(USER, 11, StudyLearningRecordScope.NODE, position),
            StudyLearningRecordsCursorPolicy.encode(USER, NODE, StudyLearningRecordScope.SUBTREE, position),
            "not-a-cursor",
        )
        for (cursor in invalid) expectApiFailure(ApiErrorCode.VALIDATION_ERROR) { fixture.page(cursor = cursor) }

        assertThat(fixture.keys.calls).isEmpty()
        assertThat(fixture.voices.batchReads).isEmpty()
    }

    @Test
    fun `node and subtree requests retain the selected ID and exact cursor position`(): Unit = runBlocking {
        val fixture = Fixture()
        val position = key(StudyLearningRecordSource.VOICE_TUTOR, 9, studyId = 12)
        val cursor = StudyLearningRecordsCursorPolicy.encode(USER, NODE, StudyLearningRecordScope.SUBTREE, position)
        fixture.page()
        fixture.page(scope = "subtree", cursor = cursor)

        assertThat(fixture.keys.calls).containsExactly(
            KeyRead(USER, NODE, StudyLearningRecordScope.NODE, 31, null),
            KeyRead(USER, NODE, StudyLearningRecordScope.SUBTREE, 31, position.cursor()),
        )
    }

    @Test
    fun `limits are bounded before adding exactly one lookahead key`(): Unit = runBlocking {
        for ((requested, expected) in listOf(Int.MIN_VALUE to 1, 0 to 1, 30 to 30, 101 to 100, Int.MAX_VALUE to 100)) {
            val fixture = Fixture()
            val result = fixture.page(limit = requested)

            assertThat(result.limit).isEqualTo(expected)
            assertThat(fixture.keys.calls.single().limit).isEqualTo(expected + 1)
            assertThat(result.items).isEmpty()
            assertThat(result.hasMore).isFalse()
            assertThat(result.nextCursor).isNull()
            assertThat(fixture.questions.calls).isEmpty()
            assertThat(fixture.voices.batchReads).isEmpty()
        }
    }

    @Test
    fun `mixed page preserves key order and batches only consumed IDs without changing question history`(): Unit = runBlocking {
        val fixture = Fixture()
        val question = question(9)
        val selectedVoice = voice(9)
        fixture.keys.rows = listOf(
            key(StudyLearningRecordSource.QUESTION, 9),
            key(StudyLearningRecordSource.VOICE_TUTOR, 9),
            key(StudyLearningRecordSource.VOICE_TUTOR, 8),
            key(StudyLearningRecordSource.QUESTION, 7).copy(createdAt = AT.minusSeconds(1)), // Older lookahead must not be hydrated.
        )
        fixture.questions.rows = listOf(question)
        fixture.voices.rows = listOf(voice(8), selectedVoice) // Deliberately different hydration order.

        val result = fixture.page(limit = 3, language = "en", view = "original")

        assertThat(result.items.map { it.id }).containsExactly("question:9", "voice:9", "voice:8")
        assertThat(result.items.map { it.studyId }).containsOnly(NODE)
        assertThat(result.items.first().questionRecord).isSameAs(question)
        assertThat(result.items.first().voiceRecord).isNull()
        assertThat(question.isPublic).isFalse()
        assertThat(question.gradingResult?.score).isEqualTo(88)
        assertThat(question.difficulty).isEqualTo(4)
        assertThat(fixture.questions.calls).containsExactly(QuestionRead(PRINCIPAL, listOf(9), "en", "original"))
        assertThat(fixture.voices.batchReads).containsExactly(USER to listOf(9L, 8L))
        assertThat(result.hasMore).isTrue()
        assertThat(StudyLearningRecordsCursorPolicy.decode(result.nextCursor, USER, NODE, StudyLearningRecordScope.NODE))
            .isEqualTo(fixture.keys.rows[2].cursor())
        assertThat(fixture.localizations.reads).isEmpty()
    }

    @Test
    fun `deleted reparented or foreign hydration is skipped while cursor advances past all consumed keys`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.keys.rows = listOf(
            key(StudyLearningRecordSource.QUESTION, 9),
            key(StudyLearningRecordSource.VOICE_TUTOR, 8),
            key(StudyLearningRecordSource.VOICE_TUTOR, 7),
            key(StudyLearningRecordSource.VOICE_TUTOR, 6),
            key(StudyLearningRecordSource.VOICE_TUTOR, 5),
        )
        fixture.questions.rows = listOf(question(9).copy(studyId = 12))
        fixture.voices.rows = listOf(voice(8).copy(userId = 99), voice(7).copy(studyId = 12))
        // Voice 6 was deleted after key selection. Every consumed key now has no valid content.
        val first = fixture.page(limit = 4)

        assertThat(first.items).isEmpty()
        assertThat(first.hasMore).isTrue()
        val position = StudyLearningRecordsCursorPolicy.decode(first.nextCursor, USER, NODE, StudyLearningRecordScope.NODE)
        assertThat(position).isEqualTo(fixture.keys.rows[3].cursor())
        assertThat(fixture.localizations.reads).isEmpty()

        fixture.keys.rows = listOf(key(StudyLearningRecordSource.VOICE_TUTOR, 5))
        fixture.voices.rows = listOf(voice(5))
        val next = fixture.page(limit = 4, cursor = first.nextCursor)
        assertThat(fixture.keys.calls.last().cursor).isEqualTo(position)
        assertThat(next.items.map { it.id }).containsExactly("voice:5")
        assertThat(next.hasMore).isFalse()
        assertThat(next.nextCursor).isNull()
    }

    @Test
    fun `exact last page has no cursor and subtree items keep their own node IDs`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.keys.rows = listOf(
            key(StudyLearningRecordSource.QUESTION, 9, studyId = 11),
            key(StudyLearningRecordSource.VOICE_TUTOR, 8, studyId = 12),
        )
        fixture.questions.rows = listOf(question(9).copy(studyId = 11))
        fixture.voices.rows = listOf(voice(8).copy(studyId = 12, parentStudyId = 11))

        val result = fixture.page(scope = "subtree", limit = 2)

        assertThat(result.items.map { it.studyId }).containsExactly(11L, 12L)
        assertThat(result.items[1].voiceRecord?.parentStudyId).isEqualTo(11L)
        assertThat(result.items[1].voiceRecord?.difficulty).isEqualTo(6)
        assertThat(result.hasMore).isFalse()
        assertThat(result.nextCursor).isNull()
    }

    @Test
    fun `single private voice detail checks owner and never exposes a foreign record`(): Unit = runBlocking {
        for (record in listOf(null, voice(9).copy(userId = 99))) {
            val fixture = Fixture().apply { voices.detail = record }
            expectApiFailure(ApiErrorCode.RECORD_NOT_FOUND, HttpStatus.NOT_FOUND) {
                fixture.service.voiceLearningRecord(PRINCIPAL, 9, "en", "localized")
            }
            assertThat(fixture.voices.singleReads).containsExactly(USER to 9L)
            assertThat(fixture.localizations.reads).isEmpty()
            assertThat(fixture.localizations.requests).isEmpty()
        }
    }

    @Test
    fun `original view and matching source language bypass translations without rewriting evidence`(): Unit = runBlocking {
        val source = voice(9)
        for ((language, view) in listOf("en" to "original", "ja" to "original", "ko" to "localized")) {
            val fixture = Fixture().apply { voices.detail = source }
            val result = fixture.service.voiceLearningRecord(PRINCIPAL, source.id, language, view)

            assertOriginalFields(result, source)
            assertIdentityAndAssessment(result, source)
            assertThat(result.requestedLanguage).isEqualTo(language)
            assertThat(result.displayLanguage).isEqualTo("ko")
            assertThat(result.translationPending).isFalse()
            assertThat(fixture.localizations.reads).isEmpty()
            assertThat(fixture.localizations.requests).isEmpty()
            assertThat(source.question).isEqualTo("캐시가 무엇인가요?")
        }
    }

    @Test
    fun `ready translation overlays text only and source remains available unchanged`(): Unit = runBlocking {
        val fixture = Fixture()
        val source = voice(9)
        fixture.voices.detail = source
        val translated = source.translatableFields().mapValues { (key, _) -> "English $key" }
        fixture.localizations.value = snapshot(source, fields = translated)

        val localized = fixture.service.voiceLearningRecord(PRINCIPAL, 9, "en", "localized")
        val original = fixture.service.voiceLearningRecord(PRINCIPAL, 9, "en", "original")

        assertThat(localized.question).isEqualTo("English question")
        assertThat(localized.answer).isEqualTo("English answer")
        assertThat(localized.feedback).isEqualTo("English feedback")
        assertThat(localized.depthSummary).isEqualTo("English depthSummary")
        assertThat(localized.strengths).containsExactly("English strength0", "English strength1")
        assertThat(localized.improvements).containsExactly("English improvement0")
        assertThat(localized.displayLanguage).isEqualTo("en")
        assertThat(localized.sourceLanguage).isEqualTo("ko")
        assertThat(localized.translationPending).isFalse()
        assertIdentityAndAssessment(localized, source)
        assertOriginalFields(original, source)
        assertThat(fixture.voices.detail).isSameAs(source)
        assertThat(fixture.localizations.requests).isEmpty()
    }

    @Test
    fun `missing or pending translation returns intact source and requests durable read repair`(): Unit = runBlocking {
        val source = voice(9)
        for (state in listOf(null, "PENDING", "PROCESSING")) {
            val fixture = Fixture().apply {
                voices.detail = source
                localizations.value = state?.let { snapshot(source, status = it, fields = emptyMap()) }
            }
            val result = fixture.service.voiceLearningRecord(PRINCIPAL, 9, "en", "localized")

            assertOriginalFields(result, source)
            assertIdentityAndAssessment(result, source)
            assertThat(result.displayLanguage).isEqualTo("ko")
            assertThat(result.translationPending).isTrue()
            assertThat(fixture.localizations.requests).containsExactly(source to "en")
        }
    }

    @Test
    fun `failed or unavailable translation still returns source without claiming a completed translation`(): Unit = runBlocking {
        val source = voice(9)
        val failed = Fixture().apply {
            voices.detail = source
            localizations.value = snapshot(source, status = "FAILED", fields = mapOf("question" to "Stale partial"))
        }
        val result = failed.service.voiceLearningRecord(PRINCIPAL, 9, "en", "localized")
        assertOriginalFields(result, source)
        assertThat(result.translationPending).isFalse()
        assertThat(result.displayLanguage).isEqualTo("ko")

        for (failDuringRequest in listOf(false, true)) {
            val unavailable = Fixture().apply {
                voices.detail = source
                if (failDuringRequest) localizations.requestFailure = IllegalStateException("Synthetic offline queue")
                else localizations.snapshotFailure = IllegalStateException("Synthetic offline store")
            }
            val fallback = unavailable.service.voiceLearningRecord(PRINCIPAL, 9, "en", "localized")
            assertOriginalFields(fallback, source)
            assertThat(fallback.translationPending).isFalse()
        }
    }

    @Test
    fun `stale wrong-language or incomplete ready snapshot cannot mix translated and source text`(): Unit = runBlocking {
        val source = voice(9)
        val complete = source.translatableFields().mapValues { (key, _) -> "Translated $key" }
        val invalid = listOf(
            snapshot(source, fields = complete).copy(sourceHash = "stale-hash"),
            snapshot(source, fields = complete).copy(targetLanguage = "ja"),
            snapshot(source, fields = complete - "answer"),
            snapshot(source, fields = complete + ("strength0" to "  ")),
            snapshot(source, status = "FAILED", fields = complete).copy(sourceHash = "stale-hash"),
        )
        for (value in invalid) {
            val fixture = Fixture().apply { voices.detail = source; localizations.value = value }
            val result = fixture.service.voiceLearningRecord(PRINCIPAL, 9, "en", "localized")

            assertOriginalFields(result, source)
            assertThat(result.translationPending).isTrue()
            assertThat(fixture.localizations.requests).containsExactly(source to "en")
        }
    }

    @Test
    fun `per-field languages trigger translation but nullable learner evidence never becomes an invented grade`(): Unit = runBlocking {
        val source = voice(9).copy(
            kind = VoiceTutorExchangeKind.LEARNER_QUESTION, answer = null, score = null, feedback = null,
            strengths = emptyList(), improvements = emptyList(), depthSummary = "",
            answerTurnIds = emptyList(), feedbackTurnIds = emptyList(), sourceLanguage = "ko",
            sourceLanguages = mapOf("question" to "en"), question = "Why Redis?",
        )
        val fixture = Fixture().apply {
            voices.detail = source
            localizations.value = snapshot(source, fields = mapOf("question" to "왜 Redis인가요?")).copy(targetLanguage = "ko")
        }

        val result = fixture.service.voiceLearningRecord(PRINCIPAL, 9, "ko", "localized")

        assertThat(result.question).isEqualTo("왜 Redis인가요?")
        assertThat(result.answer).isNull()
        assertThat(result.score).isNull()
        assertThat(result.feedback).isNull()
        assertThat(result.depthSummary).isEmpty()
        assertThat(result.strengths).isEmpty()
        assertThat(result.improvements).isEmpty()
        assertThat(result.answerTurnIds).isEmpty()
        assertIdentityAndAssessment(result, source)
        assertThat(fixture.localizations.reads).containsExactly(9L to "ko")
        assertThat(fixture.localizations.requests).isEmpty()
    }

    @Test
    fun `cancellation in translation is propagated rather than converted into a successful source response`(): Unit = runBlocking {
        for (failDuringRequest in listOf(false, true)) {
            val cancellation = CancellationException("Synthetic request cancelled")
            val fixture = Fixture().apply {
                voices.detail = voice(9)
                if (failDuringRequest) localizations.requestFailure = cancellation
                else localizations.snapshotFailure = cancellation
            }
            val failure = runCatching { fixture.service.voiceLearningRecord(PRINCIPAL, 9, "en", "localized") }.exceptionOrNull()
            assertThat(failure).isSameAs(cancellation)
        }
    }

    private class Fixture {
        val studies = StudyFixture()
        val keys = KeysFixture()
        val voices = VoiceFixture()
        val questions = QuestionFixture()
        val localizations = LocalizationFixture()
        val service = StudyLearningRecordService(studies, keys, voices, localizations, questions)

        suspend fun page(scope: String = "node", limit: Int = 30, cursor: String? = null, language: String = "ko", view: String = "localized") =
            service.learningRecords(PRINCIPAL, NODE, scope, limit, cursor, language, view)
    }

    private class StudyFixture : StudyPort by unsupportedPort() {
        var row: StudyEntity? = StudyEntity(id = NODE, userId = USER, topic = "Cache")
        val reads = mutableListOf<Pair<Long, Long>>()
        override suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? {
            reads += id to userId
            return row?.takeIf { it.id == id && it.userId == userId }
        }
    }

    private data class KeyRead(val userId: Long, val studyId: Long, val scope: StudyLearningRecordScope, val limit: Int, val cursor: StudyLearningRecordsCursor?)

    private class KeysFixture : StudyLearningRecordQueryPort {
        var rows = emptyList<StudyLearningRecordKey>()
        val calls = mutableListOf<KeyRead>()
        override suspend fun pageKeys(userId: Long, studyId: Long, scope: StudyLearningRecordScope, limit: Int, cursor: StudyLearningRecordsCursor?): List<StudyLearningRecordKey> {
            calls += KeyRead(userId, studyId, scope, limit, cursor)
            return rows.take(limit)
        }
    }

    private data class QuestionRead(val principal: Principal, val ids: List<Long>, val language: String, val view: String)

    private class QuestionFixture : BrowseRecordsUseCase by unsupportedPort() {
        var rows = emptyList<StudyRecordResponse>()
        val calls = mutableListOf<QuestionRead>()
        override suspend fun recordsByIds(principal: Principal, ids: Collection<Long>, language: String, view: String): List<StudyRecordResponse> {
            calls += QuestionRead(principal, ids.toList(), language, view)
            return rows
        }
    }

    private class VoiceFixture : VoiceStudyLearningRecordQueryPort {
        var rows = emptyList<VoiceStudyLearningRecord>()
        var detail: VoiceStudyLearningRecord? = null
        val batchReads = mutableListOf<Pair<Long, List<Long>>>()
        val singleReads = mutableListOf<Pair<Long, Long>>()
        override suspend fun findOwned(userId: Long, recordId: Long): VoiceStudyLearningRecord? {
            singleReads += userId to recordId
            return detail
        }
        override suspend fun findAllOwned(userId: Long, recordIds: Collection<Long>): List<VoiceStudyLearningRecord> {
            batchReads += userId to recordIds.toList()
            return rows
        }
    }

    private class LocalizationFixture : VoiceStudyLearningLocalizationPort by unsupportedPort() {
        var value: TextLocalizationSnapshot? = null
        var snapshotFailure: Throwable? = null
        var requestFailure: Throwable? = null
        val reads = mutableListOf<Pair<Long, String>>()
        val requests = mutableListOf<Pair<VoiceStudyLearningRecord, String>>()
        override suspend fun snapshot(recordId: Long, targetLanguage: String): TextLocalizationSnapshot? {
            reads += recordId to targetLanguage
            snapshotFailure?.let { throw it }
            return value
        }
        override suspend fun request(record: VoiceStudyLearningRecord, targetLanguage: String, now: Instant) {
            requestFailure?.let { throw it }
            requests += record to targetLanguage
        }
    }

    private companion object {
        const val USER = 7L
        const val NODE = 10L
        val AT: Instant = Instant.parse("2026-08-31T03:04:05.123456Z")
        val PRINCIPAL = Principal(USER, "synthetic-device", 1, false)

        fun key(source: StudyLearningRecordSource, id: Long, studyId: Long = NODE) = StudyLearningRecordKey(source, id, studyId, AT)
        fun StudyLearningRecordKey.cursor() = StudyLearningRecordsCursor(createdAt, source, recordId)

        fun question(id: Long) = StudyRecordResponse(
            id = id.toString(), question = QuestionItemResponse("Synthetic ordinary question", createdAt = AT),
            answer = "Synthetic saved answer", gradingResult = GradingResultResponse(88, true, "Feedback", "Explanation"),
            topic = "Cache", difficulty = 4, answeredAt = AT, isPublic = false, studyId = NODE,
            questionStatus = QuestionStatus.GRADED,
        )

        fun voice(id: Long) = VoiceStudyLearningRecord(
            id = id, userId = USER, sessionId = "synthetic-session", studyId = NODE, parentStudyId = 1,
            topic = "Cache", difficulty = 6, createdAt = AT, kind = VoiceTutorExchangeKind.TUTOR_QUESTION,
            question = "캐시가 무엇인가요?", answer = "데이터를 다시 사용할 수 있게 저장해요.", score = 82,
            strengths = listOf("재사용을 설명했어요.", "저장을 언급했어요."), improvements = listOf("만료를 보완해 보세요."),
            depthSummary = "합성 학습 기록", feedback = "좋은 답변이에요.", questionTurnId = 101,
            answerTurnIds = listOf(102, 103), feedbackTurnIds = listOf(104), sourceLanguage = "ko",
            sourceLanguages = emptyMap(), sourceHash = "synthetic-source-hash",
        )

        fun snapshot(source: VoiceStudyLearningRecord, status: String = "READY", fields: Map<String, String?>) =
            TextLocalizationSnapshot(source.sourceLanguage, "en", source.sourceHash, status, fields, "synthetic")

        fun assertOriginalFields(response: VoiceStudyLearningRecordResponse, source: VoiceStudyLearningRecord) {
            assertThat(response.question).isEqualTo(source.question)
            assertThat(response.answer).isEqualTo(source.answer)
            assertThat(response.feedback).isEqualTo(source.feedback)
            assertThat(response.strengths).isEqualTo(source.strengths)
            assertThat(response.improvements).isEqualTo(source.improvements)
            assertThat(response.depthSummary).isEqualTo(source.depthSummary)
        }

        fun assertIdentityAndAssessment(response: VoiceStudyLearningRecordResponse, source: VoiceStudyLearningRecord) {
            assertThat(response.id).isEqualTo(source.id.toString())
            assertThat(response.sessionId).isEqualTo(source.sessionId)
            assertThat(response.studyId).isEqualTo(source.studyId)
            assertThat(response.parentStudyId).isEqualTo(source.parentStudyId)
            assertThat(response.topic).isEqualTo(source.topic)
            assertThat(response.difficulty).isEqualTo(source.difficulty)
            assertThat(response.createdAt).isEqualTo(source.createdAt)
            assertThat(response.kind).isEqualTo(source.kind)
            assertThat(response.score).isEqualTo(source.score)
            assertThat(response.questionTurnId).isEqualTo(source.questionTurnId)
            assertThat(response.answerTurnIds).isEqualTo(source.answerTurnIds)
            assertThat(response.feedbackTurnIds).isEqualTo(source.feedbackTurnIds)
        }

        suspend fun expectApiFailure(code: ApiErrorCode, status: HttpStatus = HttpStatus.UNPROCESSABLE_ENTITY, action: suspend () -> Unit) {
            val failure = runCatching { action() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(ApiException::class.java)
            assertThat((failure as ApiException).code).isEqualTo(code)
            assertThat(failure.status).isEqualTo(status)
        }

        inline fun <reified T> unsupportedPort(): T = java.lang.reflect.Proxy.newProxyInstance(
            T::class.java.classLoader, arrayOf(T::class.java),
        ) { _, method, _ -> error("Unexpected ${T::class.simpleName} call: ${method.name}") } as T
    }
}
