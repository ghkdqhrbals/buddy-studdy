package com.buddystudy.backend.voice

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.application.model.toResponse
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorLearningExchange
import com.buddystudy.voice.domain.VoiceTutorResult
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceTutorExplorationResponseTest {
    @Test
    fun `existing voice results expose empty explorations without changing existing summary fields`() {
        val response = result().toResponse()
        val json = JsonMapperProvider.mapper.valueToTree<JsonNode>(response)

        assertThat(response.summaryMarkdown).isEqualTo("Existing summary")
        assertThat(response.explorations).isEmpty()
        assertThat(json.path("explorations").isArray).isTrue()
        assertThat(json.path("explorations").size()).isZero()
        assertThat(response.status).isEqualTo(VoiceTutorResultStatus.COMPLETED)
    }

    @Test
    fun `HTTP result mapping preserves child difficulty question kinds zero grade and evidence IDs`() {
        val scored = VoiceTutorLearningExchange(VoiceTutorExchangeKind.TUTOR_QUESTION, "Question", "Answer", 0, emptyList(), listOf("Review"), 1, listOf(2), listOf(3))
        val followUp = VoiceTutorLearningExchange(VoiceTutorExchangeKind.LEARNER_QUESTION, "Why?", "Explanation", null, emptyList(), emptyList(), 4, listOf(5), emptyList())
        val stored = result().copy(explorations = listOf(VoiceTutorExploration("Child topic", 43, 7, "Compared two eviction approaches", listOf(scored, followUp))))
        val response = stored.toResponse()
        val json = JsonMapperProvider.mapper.valueToTree<JsonNode>(response)

        assertThat(response.explorations.single().studyId).isEqualTo(43)
        assertThat(response.explorations.single().difficulty).isEqualTo(7)
        assertThat(response.explorations.single().exchanges.first().score).isZero()
        assertThat(response.explorations.single().exchanges.last().score).isNull()
        assertThat(json.path("explorations")[0].path("exchanges")[0].path("kind").textValue()).isEqualTo("TUTOR_QUESTION")
        assertThat(json.path("explorations")[0].path("exchanges")[1].path("kind").textValue()).isEqualTo("LEARNER_QUESTION")
        assertThat(json.path("explorations")[0].path("exchanges")[1].path("answerTurnIds")[0].longValue()).isEqualTo(5)
        assertThat(json.toString()).doesNotContain("questionQuota", "recordId", "providerItemId")
    }

    private fun result() = VoiceTutorResult(
        sessionId = "response-test", status = VoiceTutorResultStatus.COMPLETED, summaryMarkdown = "Existing summary",
        strengths = emptyList(), improvements = emptyList(), nextSteps = emptyList(), model = "gpt-5.4",
        promptVersion = "voice-tutor-summary-v2", errorMessage = null,
        createdAt = Instant.parse("2026-08-31T10:00:00Z"), updatedAt = Instant.parse("2026-08-31T10:00:00Z"),
    )
}
