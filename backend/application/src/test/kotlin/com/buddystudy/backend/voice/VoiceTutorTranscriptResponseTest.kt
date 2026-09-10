package com.buddystudy.backend.voice

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.application.model.toResponse
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptSource
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceTutorTranscriptResponseTest {
    @Test
    fun `history labels interrupted tutor archives and preserves their private original text`() {
        val turn = VoiceTutorTranscriptTurn(2, "session", "tutor-item", VoiceTutorTranscriptRole.TUTOR,
            "  설명을 이어가던 부분입니다.\n", 8, Instant.EPOCH, interrupted = true)
        val json = JsonMapperProvider.mapper.valueToTree<JsonNode>(turn.toResponse())
        assertThat(json.path("interrupted").asBoolean()).isTrue()
        assertThat(json.path("role").asText()).isEqualTo("TUTOR")
        assertThat(json.path("transcript").asText()).isEqualTo(turn.transcript)
        assertThat(json.has("providerItemId")).isFalse()
        assertThat(JsonMapperProvider.mapper.valueToTree<JsonNode>(turn.copy(interrupted = false).toResponse())
            .path("interrupted").asBoolean()).isFalse()
    }

    @Test
    fun `history exposes structured source while retaining exact text and keeping private item ids server side`() {
        val original = VoiceTutorTranscriptTurn(1, "session", "audio-item", VoiceTutorTranscriptRole.USER,
            "[Structured input]\nSelected: Redis\nText: 더 깊게 학습", 1, Instant.EPOCH)
        for ((turn, expected) in listOf(original to "AUDIO", original.copy(
            providerItemId = VoiceTutorTranscriptSource.STRUCTURED_ITEM_PREFIX + "selection",
        ) to "STRUCTURED_INPUT")) {
            val json = JsonMapperProvider.mapper.valueToTree<JsonNode>(turn.toResponse())
            assertThat(json.path("source").asText()).isEqualTo(expected)
            assertThat(json.path("transcript").asText()).isEqualTo(original.transcript)
            assertThat(json.path("role").asText()).isEqualTo("USER")
            assertThat(json.has("providerItemId")).isFalse()
        }
    }
}
