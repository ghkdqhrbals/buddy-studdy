package com.buddystudy.backend.voice.adapter.outbound

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorExplorationLimits
import com.buddystudy.voice.domain.VoiceTutorLearningExchange
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class VoiceTutorExplorationJsonCodecTest {
    private val mapper = JsonMapperProvider.mapper

    @Test
    fun `old absent null and empty persisted fields all decode as empty explorations`() {
        assertThat(VoiceTutorExplorationJsonCodec.decode(null)).isEmpty()
        assertThat(VoiceTutorExplorationJsonCodec.decode("null")).isEmpty()
        assertThat(VoiceTutorExplorationJsonCodec.decode("[]")).isEmpty()
    }

    @Test
    fun `durable JSON round-trip retains child metadata zero score and exact transcript IDs`() {
        val expected = listOf(exploration())
        val json = VoiceTutorExplorationJsonCodec.encode(expected)
        assertThat(VoiceTutorExplorationJsonCodec.decode(json)).isEqualTo(expected)
        assertThat(mapper.readTree(json)[0].path("exchanges")[0].path("score").intValue()).isZero()
        assertThat(json).doesNotContain("sessionId", "userId", "providerItemId")
    }

    @Test
    fun `ungraded unknown topic and unanswered deeper question keep nulls and empty evidence`() {
        val expected = exploration().copy(studyId = null, difficulty = null, exchanges = listOf(exchange().copy(
            kind = VoiceTutorExchangeKind.LEARNER_QUESTION, answer = "", score = null,
            strengths = emptyList(), improvements = emptyList(), answerTurnIds = emptyList(), feedbackTurnIds = emptyList(),
        )))
        assertThat(VoiceTutorExplorationJsonCodec.decode(VoiceTutorExplorationJsonCodec.encode(listOf(expected)))).containsExactly(expected)
    }

    @Test
    fun `unknown fields missing nullable fields wrong enums fractions and invalid score ranges are rejected`() {
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("kind", "PRACTICE") },
            { it.put("score", 85.5) },
            { it.put("score", -1) },
            { it.put("score", 101) },
            { it.remove("score") },
            { it.put("questionTurnId", 1.0) },
            { it.put("questionTurnId", 0) },
            { it.put("questionTurnId", "1") },
            { it.put("invented", "PRIVATE_GENERATED_TEXT") },
        )
        for (mutate in mutations) {
            val array = mapper.readTree(VoiceTutorExplorationJsonCodec.encode(listOf(exploration()))) as ArrayNode
            mutate(array[0].path("exchanges")[0] as ObjectNode)
            assertThatThrownBy { VoiceTutorExplorationJsonCodec.decode(array.toString()) }
                .isInstanceOf(InvalidVoiceTutorExploration::class.java).hasMessageNotContaining("PRIVATE_GENERATED_TEXT")
        }
        listOf("{}", "[null]", "[{}]", "[7]").forEach { raw ->
            assertThatThrownBy { VoiceTutorExplorationJsonCodec.decode(raw) }.isInstanceOf(InvalidVoiceTutorExploration::class.java)
        }
    }

    @Test
    fun `duplicate JSON keys and trailing JSON are rejected without retaining source values`() {
        val json = VoiceTutorExplorationJsonCodec.encode(listOf(exploration()))
        val duplicate = json.replaceFirst("\"topic\":", "\"topic\":\"PRIVATE_DUPLICATE\",\"topic\":")
        for (raw in listOf(duplicate, "$json {}")) {
            val error = runCatching { VoiceTutorExplorationJsonCodec.decode(raw) }.exceptionOrNull()
            assertThat(error).isInstanceOf(InvalidVoiceTutorExploration::class.java)
            assertThat(error!!.message).doesNotContain("PRIVATE_DUPLICATE")
            assertThat(error.cause).isNull()
        }
    }

    @Test
    fun `topic exchange total feedback and evidence limits are enforced before persistence`() {
        val oversized = listOf(
            List(13) { exploration() },
            listOf(exploration().copy(exchanges = List(13) { exchange() })),
            List(5) { exploration().copy(exchanges = List(12) { exchange() }) },
            listOf(exploration().copy(topic = "a".repeat(501))),
            listOf(exploration().copy(depthSummary = "a".repeat(1_501))),
            listOf(exploration().copy(exchanges = listOf(exchange().copy(question = "a".repeat(4_001))))),
            listOf(exploration().copy(exchanges = listOf(exchange().copy(answer = "a".repeat(4_001))))),
            listOf(exploration().copy(exchanges = listOf(exchange().copy(strengths = List(6) { "feedback" })))),
            listOf(exploration().copy(exchanges = listOf(exchange().copy(improvements = listOf("a".repeat(501)))))),
            listOf(exploration().copy(exchanges = listOf(exchange().copy(answerTurnIds = (1L..17L).toList())))),
        )
        oversized.forEach { value ->
            assertThatThrownBy { VoiceTutorExplorationJsonCodec.encode(value) }.isInstanceOf(InvalidVoiceTutorExploration::class.java)
        }
    }

    @Test
    fun `UTF8 byte cap applies even when character count is below the storage budget`() {
        val raw = "[\"${"가".repeat(90_000)}\"]"
        assertThat(raw.length).isLessThan(VoiceTutorExplorationLimits.MAX_JSON_BYTES)
        val error = runCatching { VoiceTutorExplorationJsonCodec.decode(raw) }.exceptionOrNull()
        assertThat((error as InvalidVoiceTutorExploration).reason).isEqualTo("EXPLORATIONS_BYTES")
    }

    private fun exploration() = VoiceTutorExploration("Redis eviction", 43, 7, "LRU와 LFU를 비교했습니다.", listOf(exchange()))

    private fun exchange() = VoiceTutorLearningExchange(
        VoiceTutorExchangeKind.TUTOR_QUESTION, "LRU는 무엇인가요?", "모르겠어요.",
        0, emptyList(), listOf("최근 사용 시점 기준을 복습"), 9_007_199_254_740_991L,
        listOf(9_007_199_254_740_992L), listOf(9_007_199_254_740_993L),
    )
}
