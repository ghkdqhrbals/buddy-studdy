package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class OpenAIVoiceTutorRealtimeAdapterTest {
    private val mapper = JsonMapperProvider.mapper

    @Test
    fun `safety identifier is a stable keyed pseudonym rather than a bare user id hash`() {
        val first = VoiceTutorSafetyIdentifier.create(42, "test-secret-a")
        val repeated = VoiceTutorSafetyIdentifier.create(42, "test-secret-a")
        val differentSecret = VoiceTutorSafetyIdentifier.create(42, "test-secret-b")

        assertThat(first).hasSize(64).isEqualTo(repeated)
        assertThat(first).isNotEqualTo(differentSecret)
        assertThat(first).doesNotContain("42")
    }

    @Test
    fun `session owns response creation and disables provider auto interruption`() {
        val adapter = OpenAIVoiceTutorRealtimeAdapter(BuddyStudyProperties())
        val update = mapper.readTree(
            adapter.sessionUpdate(
                VoiceTutorRealtimeRequest(
                    userId = 1,
                    model = "gpt-realtime-test",
                    voice = "marin",
                    instructions = "Tutor safely.",
                ),
            ),
        )
        val input = update.path("session").path("audio").path("input")
        val output = update.path("session").path("audio").path("output")
        val turnDetection = input.path("turn_detection")

        assertThat(input.path("format").path("rate").asInt()).isEqualTo(24_000)
        assertThat(output.path("format").path("rate").asInt()).isEqualTo(24_000)
        assertThat(turnDetection.path("type").asText()).isEqualTo("server_vad")
        assertThat(turnDetection.path("create_response").asBoolean()).isFalse()
        assertThat(turnDetection.path("interrupt_response").asBoolean()).isFalse()
    }

    @Test
    fun `ordinary user turn creates exactly one server owned response after commit`() {
        val controller = controller()

        StepVerifier.create(controller.providerEvents().take(1))
            .then {
                assertThat(controller.observeProviderEvent(event("input_audio_buffer.speech_started"))).isTrue()
                assertThat(controller.observeProviderEvent(event("input_audio_buffer.speech_stopped"))).isTrue()
                assertThat(controller.observeProviderEvent(event("input_audio_buffer.committed"))).isTrue()
            }
            .assertNext { raw ->
                val node = mapper.readTree(raw)
                assertThat(node.path("type").asText()).isEqualTo("response.create")
                assertThat(node.path("event_id").asText()).startsWith("buddystudy-internal-duplex-turn-response-")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `user barge in waits for cancelled tutor response before replying`() {
        val controller = controller()

        StepVerifier.create(controller.providerEvents().take(1))
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "response-old"))
                assertThat(controller.observeProviderEvent(event("input_audio_buffer.speech_started"))).isTrue()
                controller.observeProviderEvent(event("input_audio_buffer.speech_stopped"))
                controller.observeProviderEvent(event("input_audio_buffer.committed"))
            }
            .expectNoEvent(Duration.ofMillis(25))
            .then {
                controller.observeProviderEvent(responseEvent("response.done", "response-old"))
            }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `tutor intervention commits once suppresses continuation barge in and avoids duplicate reply`() {
        val controller = controller()

        StepVerifier.create(controller.providerEvents())
            .then {
                assertThat(controller.observeProviderEvent(event("input_audio_buffer.speech_started"))).isTrue()
                controller.fireContinuousSpeechDeadline()
            }
            .assertNext { raw ->
                val node = mapper.readTree(raw)
                assertThat(node.path("type").asText()).isEqualTo("input_audio_buffer.commit")
                assertThat(node.path("event_id").asText()).startsWith("buddystudy-internal-duplex-commit-")
            }
            .assertNext { raw ->
                val node = mapper.readTree(raw)
                assertThat(node.path("type").asText()).isEqualTo("response.create")
                assertThat(node.path("response").path("instructions").asText())
                    .contains("speaking continuously")
                assertThat(node.path("response").path("metadata").path("buddystudy_turn").asText())
                    .isEqualTo("continuous_intervention")
            }
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "response-intervention"))
                // The provider can start a new VAD segment after a manual commit while the
                // learner is still talking. That continuation must not cancel the tutor.
                assertThat(controller.observeProviderEvent(event("input_audio_buffer.speech_started"))).isFalse()
                controller.observeProviderEvent(event("input_audio_buffer.speech_stopped"))
                controller.observeProviderEvent(event("input_audio_buffer.committed"))
                controller.observeProviderEvent(responseEvent("response.done", "response-intervention"))
            }
            .expectNoEvent(Duration.ofMillis(50))
            .thenCancel()
            .verify()

        controller.close()
    }

    @Test
    fun `drain commits final audio and settles only after transcript and response completion`() {
        val now = AtomicLong(0)
        val state = VoiceTutorProviderDrainState(mapper, now::get)
        state.observeClientEvent("""{"type":"input_audio_buffer.append","audio":"AA=="}""")
        state.observeClientEvent("""{"type":"response.create"}""")

        val finalCommit = mapper.readTree(state.beginDrain())
        assertThat(finalCommit.path("type").asText()).isEqualTo("input_audio_buffer.commit")
        assertThat(state.isSettled()).isFalse()

        state.observeProviderEvent("""{"type":"input_audio_buffer.committed","item_id":"item-user"}""")
        state.observeProviderEvent("""{"type":"response.created","response":{"id":"response-1"}}""")
        state.observeProviderEvent(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item-user"}""",
        )
        state.observeProviderEvent("""{"type":"response.done","response":{"id":"response-1"}}""")
        now.set(151_000_000)

        assertThat(state.isSettled()).isTrue()
    }

    @Test
    fun `drain grace is bounded when provider never completes`() {
        val state = VoiceTutorProviderDrainState(mapper)
        state.observeClientEvent("""{"type":"input_audio_buffer.append","audio":"AA=="}""")
        assertThat(state.beginDrain()).isNotNull()

        state.awaitDrain(Duration.ofMillis(50)).block(Duration.ofSeconds(1))
    }

    private fun controller() = VoiceTutorDuplexTurnController(
        mapper = mapper,
        continuousSpeechLimit = Duration.ofSeconds(30),
    )

    private fun event(type: String): String = """{"type":"$type"}"""

    private fun responseEvent(type: String, id: String): String =
        """{"type":"$type","response":{"id":"$id"}}"""
}
