package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

        val cancel = mapper.readTree(adapter.responseCancelEvent("relay-terminal"))
        assertThat(cancel.path("type").asText()).isEqualTo("response.cancel")
        assertThat(cancel.path("event_id").asText()).startsWith("buddystudy-internal-relay-terminal-")
    }

    @Test
    fun `ordinary user turn creates exactly one server owned response after commit`() {
        val controller = controller()

        StepVerifier.create(controller.providerEvents().take(1))
            .then { queueOrdinaryResponse(controller) }
            .assertNext { raw ->
                val node = mapper.readTree(raw)
                assertThat(node.path("type").asText()).isEqualTo("response.create")
                assertThat(node.path("event_id").asText()).startsWith("buddystudy-internal-duplex-turn-response-")
                assertThat(
                    node.path("response").path("metadata")
                        .path(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY).asText(),
                ).isEqualTo(node.path("event_id").asText())
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `overlap waits for provider done and rendered sentence acknowledgement`() {
        val controller = controller()
        val activeControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents().take(2))
            .then { queueOrdinaryResponse(controller) }
            .assertNext(activeControl::set)
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "response-old", activeControl.get()))
                controller.observeProviderEvent(audioDeltaEvent("response-old"))
                learnerTurn(controller)
                controller.observeProviderEvent(responseEvent("response.done", "response-old", activeControl.get()))
            }
            .expectNoEvent(Duration.ofMillis(25))
            .then { controller.observeClientEvent(playbackCompletedEvent("response-old")) }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `forged early acknowledgement cannot beat the audio duration floor`() {
        val now = AtomicLong(0)
        val controller = controller(now::get)
        val activeControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents().take(2))
            .then { queueOrdinaryResponse(controller) }
            .assertNext(activeControl::set)
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "response-old", activeControl.get()))
                controller.observeProviderEvent(audioDeltaEvent("response-old", byteCount = 48_000))
                learnerTurn(controller)
                controller.observeClientEvent(playbackCompletedEvent("response-old"))
                controller.observeProviderEvent(responseEvent("response.done", "response-old", activeControl.get()))
                controller.firePlaybackFloor("response-old")
            }
            .expectNoEvent(Duration.ofMillis(25))
            .then {
                now.set(1_000_000_000)
                controller.firePlaybackFloor("response-old")
            }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `audio gaps extend the earliest safe playback completion`() {
        val now = AtomicLong(0)
        val controller = controller(now::get)
        val activeControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents().take(2))
            .then { queueOrdinaryResponse(controller) }
            .assertNext(activeControl::set)
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "response-gap", activeControl.get()))
                controller.observeProviderEvent(audioDeltaEvent("response-gap", byteCount = 960))
                controller.observeClientEvent(playbackCompletedEvent("response-gap"))
                now.set(20_000_000_000)
                controller.observeProviderEvent(audioDeltaEvent("response-gap", byteCount = 480_000))
                learnerTurn(controller)
                controller.observeProviderEvent(responseEvent("response.done", "response-gap", activeControl.get()))
                controller.firePlaybackFloor("response-gap")
            }
            .expectNoEvent(Duration.ofMillis(25))
            .then {
                now.set(30_000_000_000)
                controller.firePlaybackFloor("response-gap")
            }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `stale acknowledgement and timeout cannot release the active response`() {
        val now = AtomicLong(1_000_000_000)
        val controller = controller(now::get)
        val activeControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents().take(2))
            .then { queueOrdinaryResponse(controller) }
            .assertNext(activeControl::set)
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "response-current", activeControl.get()))
                controller.observeProviderEvent(audioDeltaEvent("response-current"))
                learnerTurn(controller)
                controller.observeProviderEvent(responseEvent("response.done", "response-current", activeControl.get()))
                controller.observeClientEvent(playbackCompletedEvent("response-stale"))
                controller.firePlaybackTimeout("response-stale")
            }
            .expectNoEvent(Duration.ofMillis(25))
            .then {
                now.addAndGet(1_000_000)
                controller.observeClientEvent(playbackCompletedEvent("response-current"))
            }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `older delayed commit cannot clear a newer speech segment`() {
        val controller = controller()
        val activeControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents().take(2))
            .then { queueOrdinaryResponse(controller) }
            .assertNext(activeControl::set)
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "response-old", activeControl.get()))
                controller.observeProviderEvent(audioDeltaEvent("response-old"))
                controller.observeProviderEvent(event("input_audio_buffer.speech_started"))
                controller.observeProviderEvent(event("input_audio_buffer.speech_stopped"))
                controller.observeProviderEvent(event("input_audio_buffer.speech_started"))
                controller.observeProviderEvent(event("input_audio_buffer.committed"))
                controller.observeProviderEvent(responseEvent("response.done", "response-old", activeControl.get()))
                controller.observeClientEvent(playbackCompletedEvent("response-old"))
                controller.observeProviderEvent(event("input_audio_buffer.speech_stopped"))
            }
            .expectNoEvent(Duration.ofMillis(25))
            .then { controller.observeProviderEvent(event("input_audio_buffer.committed")) }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `partial response audio still waits for device playback`() {
        val controller = controller()
        val activeControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents().take(2))
            .then { queueOrdinaryResponse(controller) }
            .assertNext(activeControl::set)
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "response-partial", activeControl.get()))
                controller.observeProviderEvent(audioDeltaEvent("response-partial"))
                learnerTurn(controller)
                controller.observeProviderEvent(
                    responseEvent("response.done", "response-partial", activeControl.get(), status = "incomplete"),
                )
            }
            .expectNoEvent(Duration.ofMillis(25))
            .then { controller.observeClientEvent(playbackCompletedEvent("response-partial")) }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `unsolicited response and stale create error cannot claim active generation`() {
        val controller = controller()
        val activeControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents().take(2))
            .then {
                assertThat(
                    controller.observeProviderEvent(
                        """{"type":"response.created","response":{"id":"response-unsolicited","metadata":{}}}""",
                    ),
                ).isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
                queueOrdinaryResponse(controller)
            }
            .assertNext(activeControl::set)
            .then {
                controller.observeProviderEvent(
                    """{"type":"error","error":{"event_id":"buddystudy-internal-duplex-stale"}}""",
                )
                learnerTurn(controller)
            }
            .expectNoEvent(Duration.ofMillis(25))
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "response-current", activeControl.get()))
                controller.observeProviderEvent(responseEvent("response.done", "response-current", activeControl.get()))
            }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `failed active response cannot release a queued learner turn`() {
        val controller = controller()
        val activeControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents().take(2))
            .then { queueOrdinaryResponse(controller) }
            .assertNext(activeControl::set)
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "response-failed", activeControl.get()))
                learnerTurn(controller)
                val disposition = controller.observeProviderEvent(
                    responseEvent("response.done", "response-failed", activeControl.get(), status = "failed"),
                )
                assertThat(disposition).isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST)
            }
            .expectNoEvent(Duration.ofMillis(25))
            .thenCancel()
            .verify()

        controller.close()
    }

    @Test
    fun `response timeout cancels the provider and fails the relay`() {
        val controller = controller()
        val activeControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents())
            .then { queueOrdinaryResponse(controller) }
            .assertNext { raw ->
                activeControl.set(raw)
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
            }
            .then {
                val createEventId = mapper.readTree(activeControl.get()).path("event_id").asText()
                controller.fireResponseTimeout(createEventId)
            }
            .assertNext { raw ->
                val node = mapper.readTree(raw)
                assertThat(node.path("type").asText()).isEqualTo("response.cancel")
                assertThat(node.path("event_id").asText())
                    .startsWith("buddystudy-internal-response-timeout-")
            }
            .expectError(VoiceTutorProviderResponseTimeoutException::class.java)
            .verify()
    }

    @Test
    fun `stale response timeout cannot cancel the next generation`() {
        val controller = controller()
        val firstControl = AtomicReference<String>()
        val secondControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents())
            .then { queueOrdinaryResponse(controller) }
            .assertNext(firstControl::set)
            .then {
                learnerTurn(controller)
                controller.observeProviderEvent(
                    responseEvent("response.created", "response-first", firstControl.get()),
                )
                controller.observeProviderEvent(
                    responseEvent("response.done", "response-first", firstControl.get()),
                )
            }
            .assertNext(secondControl::set)
            .then {
                val firstEventId = mapper.readTree(firstControl.get()).path("event_id").asText()
                controller.fireResponseTimeout(responseGeneration = 1, createEventId = firstEventId)
            }
            .expectNoEvent(Duration.ofMillis(25))
            .then {
                val secondEventId = mapper.readTree(secondControl.get()).path("event_id").asText()
                controller.fireResponseTimeout(responseGeneration = 2, createEventId = secondEventId)
            }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.cancel")
            }
            .expectError(VoiceTutorProviderResponseTimeoutException::class.java)
            .verify()
    }

    @Test
    fun `continuous intervention leaves learner buffer for its natural commit`() {
        val controller = controller()
        val interventionControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents().take(2))
            .then {
                controller.observeProviderEvent(event("input_audio_buffer.speech_started"))
                controller.fireContinuousSpeechDeadline()
            }
            .assertNext { raw ->
                interventionControl.set(raw)
                val node = mapper.readTree(raw)
                assertThat(node.path("type").asText()).isEqualTo("response.create")
                assertThat(node.path("response").path("instructions").asText())
                    .contains("speaking continuously")
                    .contains("exactly one short, complete")
                assertThat(node.path("response").path("metadata").path("buddystudy_turn").asText())
                    .isEqualTo("continuous_intervention")
            }
            .then {
                controller.observeProviderEvent(
                    responseEvent("response.created", "response-intervention", interventionControl.get()),
                )
                controller.observeProviderEvent(event("input_audio_buffer.speech_stopped"))
                controller.observeProviderEvent(event("input_audio_buffer.committed"))
                controller.observeProviderEvent(
                    responseEvent("response.done", "response-intervention", interventionControl.get()),
                )
            }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `elapsed intervention deadline resumes after current tutor sentence`() {
        val controller = controller()
        val activeControl = AtomicReference<String>()

        StepVerifier.create(controller.providerEvents().take(2))
            .then { queueOrdinaryResponse(controller) }
            .assertNext(activeControl::set)
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "response-old", activeControl.get()))
                controller.observeProviderEvent(event("input_audio_buffer.speech_started"))
                controller.fireContinuousSpeechDeadline()
            }
            .expectNoEvent(Duration.ofMillis(25))
            .then {
                controller.observeProviderEvent(responseEvent("response.done", "response-old", activeControl.get()))
            }
            .assertNext { raw ->
                val node = mapper.readTree(raw)
                assertThat(node.path("type").asText()).isEqualTo("response.create")
                assertThat(node.path("response").path("metadata").path("buddystudy_turn").asText())
                    .isEqualTo("continuous_intervention")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `terminal blocks responses and keeps final transcripts persistence only`() {
        val controller = controller()
        val activeControl = AtomicReference<String>()
        val completed = AtomicBoolean(false)
        val emittedControls = AtomicLong(0)
        val subscription = controller.providerEvents().subscribe(
            {
                activeControl.set(it)
                emittedControls.incrementAndGet()
            },
            {},
            { completed.set(true) },
        )
        queueOrdinaryResponse(controller)
        controller.observeProviderEvent(responseEvent("response.created", "response-active", activeControl.get()))
        controller.close()

        val userTranscript = controller.observeProviderEvent(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"final"}""",
        )
        val tutorTranscript = controller.observeProviderEvent(
            """{"type":"response.output_audio_transcript.done","response_id":"response-active","transcript":"final"}""",
        )
        val staleTutorTranscript = controller.observeProviderEvent(
            """{"type":"response.output_audio_transcript.done","response_id":"response-stale","transcript":"stale"}""",
        )
        assertThat(userTranscript).isEqualTo(VoiceTutorProviderRelayDisposition.PERSIST_ONLY)
        assertThat(tutorTranscript).isEqualTo(VoiceTutorProviderRelayDisposition.PERSIST_ONLY)
        assertThat(staleTutorTranscript).isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        assertThat(controller.observeProviderEvent(audioDeltaEvent("response-late")))
            .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        learnerTurn(controller)

        assertThat(completed.get()).isTrue()
        assertThat(emittedControls.get()).isEqualTo(1)
        subscription.dispose()
    }

    @Test
    fun `drain commits final audio and settles after transcript and response completion`() {
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

    private fun controller(nanoTime: () -> Long = System::nanoTime) = VoiceTutorDuplexTurnController(
        mapper = mapper,
        continuousSpeechLimit = Duration.ofSeconds(30),
        responseTimeout = Duration.ofSeconds(60),
        nanoTime = nanoTime,
    )

    private fun queueOrdinaryResponse(controller: VoiceTutorDuplexTurnController) {
        learnerTurn(controller)
    }

    private fun learnerTurn(controller: VoiceTutorDuplexTurnController) {
        controller.observeProviderEvent(event("input_audio_buffer.speech_started"))
        controller.observeProviderEvent(event("input_audio_buffer.speech_stopped"))
        controller.observeProviderEvent(event("input_audio_buffer.committed"))
    }

    private fun event(type: String): String = """{"type":"$type"}"""

    private fun responseEvent(
        type: String,
        id: String,
        responseControl: String,
        status: String = if (type == "response.created") "in_progress" else "completed",
    ): String {
        val token = mapper.readTree(responseControl)
            .path("response")
            .path("metadata")
            .path(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY)
            .asText()
        return mapper.writeValueAsString(
            mapOf(
                "type" to type,
                "response" to mapOf(
                    "id" to id,
                    "status" to status,
                    "metadata" to mapOf(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to token),
                ),
            ),
        )
    }

    private fun audioDeltaEvent(id: String, byteCount: Int = 4): String = mapper.writeValueAsString(
        mapOf(
            "type" to "response.output_audio.delta",
            "response_id" to id,
            "delta" to Base64.getEncoder().encodeToString(ByteArray(byteCount)),
        ),
    )

    private fun playbackCompletedEvent(id: String): String =
        """{"type":"buddystudy.voice.playback.completed","responseId":"$id"}"""
}
