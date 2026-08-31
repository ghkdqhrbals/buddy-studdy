package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorLearningExchange
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** Synthetic transcript facts only: no ASR, model, database, quota or ordinary question workflow. */
class VoiceStudyLearningRecordProjectorTest {
    private val fixture = VoiceStudyLearningRecordTestFixture

    @Test
    fun `question answer and feedback are reconstructed verbatim from source turns not model paraphrases`() {
        val turns = fixture.turns()
        val record = project().single()
        assertThat(record.userId).isEqualTo(fixture.USER_ID)
        assertThat(record.sessionId).isEqualTo(fixture.SESSION_ID)
        assertThat(record.studyId).isEqualTo(11)
        assertThat(record.parentStudyId).isEqualTo(10)
        assertThat(record.topic).isEqualTo(fixture.TOPIC)
        assertThat(record.difficulty).isEqualTo(3)
        assertThat(record.question).isEqualTo(turns[0].transcript)
        assertThat(record.answer).isEqualTo(turns[1].transcript + "\n" + turns[2].transcript)
        assertThat(record.feedback).isEqualTo(turns[3].transcript)
        assertThat(record.createdAt).isEqualTo(turns[0].occurredAt)
        assertThat(record.score).isEqualTo(85)
        assertThat(record.questionTurnId).isEqualTo(1)
        assertThat(record.answerTurnIds).containsExactly(2, 3)
        assertThat(record.feedbackTurnIds).containsExactly(4)
        assertThat(record.question).isNotEqualTo(fixture.exchange().question)
        assertThat(record.answer).isNotEqualTo(fixture.exchange().answer)
    }

    @Test
    fun `changing model question answer or guessed level never changes original text or source hash`() {
        val original = project().single()
        val changed = fixture.exploration().copy(difficulty = 10, exchanges = listOf(
            fixture.exchange().copy(question = "An English paraphrase?", answer = "A translated paraphrase."),
        ))
        assertThat(project(listOf(changed)).single()).isEqualTo(original)
    }

    @Test
    fun `source sequence not numeric turn id or input list order establishes chronology`() {
        val turns = fixture.turns().map { if (it.id == 1L) it.copy(id = 100) else it }.reversed()
        val exploration = fixture.exploration().copy(exchanges = listOf(fixture.exchange().copy(questionTurnId = 100)))
        val record = project(listOf(exploration), turns).single()
        assertThat(record.questionTurnId).isEqualTo(100)
        assertThat(record.answerTurnIds).containsExactly(2, 3)
        assertThat(record.answer).isEqualTo(fixture.turns()[1].transcript + "\n" + fixture.turns()[2].transcript)
        assertThat(record.createdAt).isEqualTo(fixture.turns()[0].occurredAt)
    }

    @Test
    fun `missing foreign role mismatched duplicate and nonchronological evidence cannot create a record`() {
        val invalid = listOf(
            fixture.exchange().copy(questionTurnId = 999),
            fixture.exchange().copy(questionTurnId = 2),
            fixture.exchange().copy(answerTurnIds = listOf(4)),
            fixture.exchange().copy(answerTurnIds = listOf(2, 2)),
            fixture.exchange().copy(answerTurnIds = listOf(3, 2)),
            fixture.exchange().copy(feedbackTurnIds = listOf(1)),
            fixture.exchange().copy(feedbackTurnIds = listOf(2)),
            fixture.exchange().copy(feedbackTurnIds = listOf(4, 4)),
        )
        for (exchange in invalid) {
            assertThat(project(listOf(fixture.exploration().copy(exchanges = listOf(exchange)))))
                .describedAs(exchange.toString()).isEmpty()
        }
        assertThat(project(turns = fixture.turns().map { if (it.id == 2L) it.copy(sessionId = "foreign-call") else it })).isEmpty()
        assertThat(project(turns = fixture.turns() + fixture.turns()[1])).isEmpty()
        assertThat(project(turns = fixture.turns().map { if (it.id == 2L) it.copy(sequenceNumber = 1) else it })).isEmpty()
        assertThat(project(turns = fixture.turns().map { if (it.id == 2L) it.copy(transcript = "  ") else it })).isEmpty()
    }

    @Test
    fun `a duplicate blank turn must not replace accepted source text while retaining its grade`() {
        val source = fixture.turns()
        val records = project(turns = source + source[1].copy(transcript = " \n"))
        assertThat(records).isEmpty()
    }

    @Test
    fun `only an actually spoken score survives and quantities cannot create a new evaluation`() {
        val ungraded = project(turns = fixture.turns().map {
            if (it.id == 4L) it.copy(transcript = "캐시 응답은 85ms이고 적중률은 100%입니다.") else it
        }).single()
        assertThat(ungraded.score).isNull()
        assertThat(ungraded.strengths).isEmpty()
        assertThat(ungraded.improvements).isEmpty()
        assertThat(ungraded.feedbackTurnIds).isEmpty()
        assertThat(ungraded.feedback).isNull()
        assertThat(ungraded.answer).isEqualTo(project().single().answer)

        val unsupported = fixture.exploration().copy(exchanges = listOf(fixture.exchange().copy(score = 99)))
        assertThat(project(listOf(unsupported)).single().score).isNull()
        assertThat(project().single().score).isEqualTo(85)
    }

    @Test
    fun `a learner question and tutor answer stay ungraded even when model output invents an assessment`() {
        val exploration = fixture.exploration().copy(exchanges = listOf(fixture.learnerQuestion().copy(
            score = 85, strengths = listOf("Invented praise"), improvements = listOf("Invented gap"), feedbackTurnIds = listOf(4),
        )))
        val record = project(listOf(exploration)).single()
        assertThat(record.kind).isEqualTo(VoiceTutorExchangeKind.LEARNER_QUESTION)
        assertThat(record.question).isEqualTo(fixture.turns()[4].transcript)
        assertThat(record.answer).isEqualTo(fixture.turns()[5].transcript)
        assertThat(record.score).isNull()
        assertThat(record.strengths).isEmpty()
        assertThat(record.improvements).isEmpty()
        assertThat(record.feedbackTurnIds).isEmpty()
    }

    @Test
    fun `unanswered questions retain their source without accepting a model written answer or grade`() {
        val exploration = fixture.exploration().copy(exchanges = listOf(fixture.exchange().copy(answerTurnIds = emptyList())))
        val record = project(listOf(exploration)).single()
        assertThat(record.question).isEqualTo(fixture.turns()[0].transcript)
        assertThat(record.answer).isNull()
        assertThat(record.score).isNull()
        assertThat(record.feedback).isNull()
        assertThat(record.strengths).isEmpty()
        assertThat(record.translatableFields()).containsOnlyKeys("question", "depthSummary")
    }

    @Test
    fun `short meaningful answers remain exact original answers without a length threshold`() {
        for (answer in listOf("응", "아니", "2", "Redis")) {
            val exploration = fixture.exploration().copy(exchanges = listOf(fixture.exchange().copy(answerTurnIds = listOf(2))))
            val turns = fixture.turns().map { if (it.id == 2L) it.copy(transcript = answer) else it }
            assertThat(project(listOf(exploration), turns).single().answer).isEqualTo(answer)
        }
    }

    @Test
    fun `only currently owned existing nodes in the selected tree may receive records and names never supply ids`() {
        val outside = VoiceTutorStudySnapshot(91, 90, fixture.TOPIC, 3)
        val snapshots = fixture.snapshots() + listOf(VoiceTutorStudySnapshot(90, null, fixture.TOPIC, 3), outside)
        val foreignTree = fixture.exploration().copy(studyId = 91)
        assertThat(project(listOf(foreignTree), snapshots = snapshots, owned = setOf(11, 90, 91))).isEmpty()
        assertThat(project(owned = emptySet())).isEmpty()
        assertThat(project(owned = setOf(90, 91))).isEmpty()
        assertThat(project(listOf(fixture.exploration().copy(studyId = null)))).isEmpty()
        assertThat(project(listOf(fixture.exploration().copy(studyId = 999)))).isEmpty()
        assertThat(project(listOf(fixture.exploration().copy(topic = "Invented name")))).isEmpty()
        assertThat(project(selectedStudyId = null)).isEmpty()
        assertThat(project(snapshots = fixture.snapshots() + fixture.snapshots().last().copy(difficulty = 9))).isEmpty()
    }

    @Test
    fun `a real same root sibling can be explicitly discussed but a cross node duplicate question is never arbitrarily anchored`() {
        val sibling = VoiceTutorStudySnapshot(12, 1, "Queues", 4)
        val snapshots = fixture.snapshots() + sibling
        val siblingLesson = fixture.exploration().copy(studyId = 12, topic = sibling.topic)
        assertThat(project(listOf(siblingLesson), snapshots = snapshots, owned = setOf(11, 12)).single().studyId).isEqualTo(12)

        val independent = fixture.exploration().copy(exchanges = listOf(fixture.learnerQuestion()))
        val records = project(listOf(fixture.exploration(), siblingLesson, independent), snapshots = snapshots, owned = setOf(11, 12))
        assertThat(records.map { it.questionTurnId }).containsExactly(5)
        assertThat(records.single().studyId).isEqualTo(11)
        assertThat(project(listOf(fixture.exploration(), fixture.exploration()))).hasSize(1)
    }

    @Test
    fun `original text is never truncated and oversized source evidence stays only in the canonical call`() {
        for (id in listOf(1L, 2L, 4L)) {
            val turns = fixture.turns().map { if (it.id == id) it.copy(transcript = "85점입니다. " + "가".repeat(16_001)) else it }
            assertThat(project(turns = turns)).describedAs("oversized source $id").isEmpty()
        }
        val boundary = fixture.turns().map { if (it.id == 1L) it.copy(transcript = "가".repeat(16_000)) else it }
        assertThat(project(turns = boundary).single().question).hasSize(16_000)
    }

    @Test
    fun `large valid extraction is bounded to forty eight distinct records without inventing or merging evidence`() {
        val turns = (0L..48L).flatMap { index ->
            listOf(fixture.turn(100 + index * 2, VoiceTutorTranscriptRole.USER, "질문 $index"),
                fixture.turn(101 + index * 2, VoiceTutorTranscriptRole.TUTOR, "답변 $index"))
        }
        val exchanges = (0L..48L).map { index -> fixture.learnerQuestion().copy(
            questionTurnId = 100 + index * 2, answerTurnIds = listOf(101 + index * 2),
        ) }
        val explorations = exchanges.chunked(12).map { fixture.exploration().copy(exchanges = it) }
        val records = project(explorations, turns)
        assertThat(records).hasSize(48)
        assertThat(records.map { it.questionTurnId }).doesNotHaveDuplicates()
        assertThat(records.first().question).isEqualTo("질문 0")
        assertThat(records.last().answer).isEqualTo("답변 47")
    }

    @Test
    fun `translation source hash covers exact translatable originals and per field languages but no identity or numeric score fields`() {
        val calls = mutableListOf<Pair<String, String>>()
        val record = project(language = "ja-JP", detect = { text, fallback ->
            calls += text to fallback
            if (text == fixture.turns()[1].transcript + "\n" + fixture.turns()[2].transcript) "en-US" else "ko-KR"
        }).single()
        val fields = record.translatableFields()
        assertThat(record.sourceLanguage).isEqualTo("ko")
        assertThat(record.sourceLanguages).containsEntry("answer", "en").containsEntry("question", "ko")
        assertThat(record.sourceLanguages.keys).isEqualTo(fields.keys)
        assertThat(calls.map { it.second }).containsOnly("ja")
        assertThat(fields).containsOnlyKeys("question", "answer", "feedback", "depthSummary", "strength0", "improvement0")
        assertThat(record.sourceHash).isEqualTo(ContentSourceHashPolicy.sha256(JsonMapperProvider.mapper.writeValueAsString(
            linkedMapOf("fields" to fields, "languages" to record.sourceLanguages),
        )))
        assertThat(record.sourceHash).hasSize(64)
        assertThat(project().single().sourceHash).isNotEqualTo(record.sourceHash)
    }

    private fun project(
        explorations: List<VoiceTutorExploration> = listOf(fixture.exploration()),
        turns: List<VoiceTutorTranscriptTurn> = fixture.turns(),
        snapshots: List<VoiceTutorStudySnapshot> = fixture.snapshots(),
        owned: Set<Long> = setOf(11),
        selectedStudyId: Long? = 10,
        language: String = "ko",
        detect: (String, String) -> String = { _, fallback -> fallback },
    ) = VoiceStudyLearningRecordProjector.project(
        fixture.USER_ID, fixture.SESSION_ID, selectedStudyId, language, explorations, turns, snapshots, owned, detect,
    )
}

/** Shared only by the two isolated projector/persistence test classes. */
internal object VoiceStudyLearningRecordTestFixture {
    const val USER_ID = 7L
    const val SESSION_ID = "synthetic-voice-learning"
    const val TOPIC = "Redis eviction"
    val now: Instant = Instant.parse("2026-08-31T12:00:00.123456Z")

    fun snapshots() = listOf(
        VoiceTutorStudySnapshot(1, null, "Systems", 9),
        VoiceTutorStudySnapshot(10, 1, "Redis", 6),
        VoiceTutorStudySnapshot(11, 10, TOPIC, 3),
    )

    fun exploration() = VoiceTutorExploration(
        TOPIC, 11, 9, "LRU와 LFU의 교체 기준을 비교했습니다.", listOf(exchange()),
    )

    fun exchange() = VoiceTutorLearningExchange(
        VoiceTutorExchangeKind.TUTOR_QUESTION, "LLM이 간추린 질문입니다.", "LLM이 다시 쓴 답변입니다.",
        85, listOf("최근 사용 기준을 이해함"), listOf("접근 시점 갱신을 보완"), 1, listOf(2, 3), listOf(4),
    )

    fun learnerQuestion() = VoiceTutorLearningExchange(
        VoiceTutorExchangeKind.LEARNER_QUESTION, "LLM이 간추린 추가 질문입니다.", "LLM이 간추린 설명입니다.",
        null, emptyList(), emptyList(), 5, listOf(6), emptyList(),
    )

    fun turns() = listOf(
        turn(1, VoiceTutorTranscriptRole.TUTOR, "  캐시에서 **LRU**는 어떤 키를 제거하나요?\n원문 질문입니다. "),
        turn(2, VoiceTutorTranscriptRole.USER, "  오래전에 쓴 키부터요.\n"),
        turn(3, VoiceTutorTranscriptRole.USER, "빈도보다는 최근 사용 시점으로 판단해요. \n"),
        turn(4, VoiceTutorTranscriptRole.TUTOR, "85점입니다. 최근 사용 기준을 잘 짚었고, 접근 시점 갱신을 덧붙이면 좋아요. "),
        turn(5, VoiceTutorTranscriptRole.USER, "LFU랑은 어떻게 달라요?"),
        turn(6, VoiceTutorTranscriptRole.TUTOR, "LFU는 사용 빈도가 낮은 키를 먼저 제거해요."),
    )

    fun turn(id: Long, role: VoiceTutorTranscriptRole, text: String) = VoiceTutorTranscriptTurn(
        id, SESSION_ID, "synthetic-item-$id", role, text, id, now.minusSeconds(30).plusSeconds(id),
    )
}
