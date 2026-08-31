package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Real serialized duplex/input/MCP state, with synthetic events and no external work. */
class VoiceTutorPauseControlTest {
    @Test
    fun `pause drains the current sentence through both existing provider boundaries before acknowledging`(): Unit =
        Fixture().use { f ->
            val response = f.startTutorResponse()
            f.client(VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT, 1)
            f.client(VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT, 1)
            f.controller.advancePause()
            assertThat(f.events("input_audio_buffer.clear")).isEmpty()
            assertThat(f.acknowledgements).isEmpty()
            f.response("response.done", "tutor", response)
            f.controller.advancePause()
            assertThat(f.events("input_audio_buffer.clear")).isEmpty()
            f.provider("output_audio_buffer.stopped", "response_id" to "unrelated")
            f.controller.advancePause()
            assertThat(f.events("input_audio_buffer.clear")).isEmpty()
            f.provider("output_audio_buffer.stopped", "response_id" to "tutor")
            f.controller.advancePause()
            assertThat(f.events("input_audio_buffer.clear")).hasSize(1)
            assertThat(f.acknowledgements).isEmpty()
            f.clearAck("pause")
            f.assertAcknowledgement(1, paused = true)
            assertThat(f.events("response.create")).hasSize(1)
            f.assertNoOutputDisruption()
            assertThat(f.errors).isEmpty()
        }

    @Test
    fun `stopped before response done remains the same two-signal pause boundary`(): Unit = Fixture().use { f ->
        val response = f.startTutorResponse()
        f.requestPause()
        f.provider("output_audio_buffer.stopped", "response_id" to "tutor")
        f.controller.advancePause()
        assertThat(f.events("input_audio_buffer.clear")).isEmpty()
        f.response("response.done", "tutor", response)
        f.controller.advancePause()
        assertThat(f.events("input_audio_buffer.clear")).hasSize(1)
        f.clearAck("pause")
        f.assertAcknowledgement(1, paused = true)
        f.assertNoOutputDisruption()
    }

    @Test
    fun `pause stop and commit ACK cannot race a clear or lose the queued learner answer`(): Unit = Fixture().use { f ->
        f.speechStarted(1)
        f.client(VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT, 1)
        f.speechStopped(1)
        assertThat(f.events("input_audio_buffer.commit")).hasSize(1)
        f.controller.advancePause()
        assertThat(f.events("input_audio_buffer.clear")).isEmpty()
        f.client(VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT, 1)
        f.controller.advancePause()
        assertThat(f.events("input_audio_buffer.clear")).isEmpty()
        f.provider("input_audio_buffer.committed", "item_id" to "pre-pause-answer")
        f.controller.advancePause()
        assertThat(f.events("input_audio_buffer.clear")).hasSize(1)
        assertThat(f.events("response.create")).isEmpty()
        f.clearAck("pause")
        f.assertAcknowledgement(1, paused = true)
        f.client(VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT, 2)
        assertThat(f.events("input_audio_buffer.clear")).hasSize(2)
        assertThat(f.events("response.create")).isEmpty()
        f.clearAck("resume")
        f.assertAcknowledgement(2, paused = false)
        assertThat(f.events("response.create")).hasSize(1)
        assertThat(f.events("conversation.item.delete")).isEmpty()
        f.assertNoOutputDisruption()
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `quiescence alone cannot bypass the delayed tail commit after a continuous-speech checkpoint`(): Unit =
        Fixture(withAssessment = true).use { f ->
            f.speechStarted(1)
            f.controller.fireContinuousSpeechDeadline()
            assertThat(f.events("input_audio_buffer.commit")).hasSize(1)
            f.client(VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT, 1)
            f.speechStopped(1)
            f.client(VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT, 1)
            f.provider("input_audio_buffer.committed", "item_id" to "checkpoint")
            f.controller.advancePause()
            assertThat(f.events("input_audio_buffer.clear")).isEmpty()
            f.now.set(Duration.ofMillis(250).toNanos())
            f.controller.flushDelayedStopCommit(1)
            assertThat(f.events("input_audio_buffer.commit")).hasSize(2)
            f.controller.advancePause()
            assertThat(f.events("input_audio_buffer.clear")).isEmpty()
            f.provider("input_audio_buffer.committed", "item_id" to "tail")
            f.controller.advancePause()
            assertThat(f.events("input_audio_buffer.clear")).hasSize(1)
            f.clearAck("pause")
            f.assertAcknowledgement(1, paused = true)
            assertThat(f.events("response.create")).isEmpty()
            f.assertNoOutputDisruption()
        }

    @Test
    fun `paused speech edges and long-speech callbacks cannot commit clear another turn or create speech`(): Unit =
        Fixture().use { f ->
            f.pause()
            val controls = f.controls.size
            f.speechStarted(7)
            f.controller.fireContinuousSpeechDeadline()
            f.speechStopped(7)
            f.controller.advancePause()
            assertThat(f.controls).hasSize(controls)
            f.resume()
            val resumedControls = f.controls.size
            // An already rejected held edge remains rejected after resume.
            f.speechStarted(7)
            f.speechStopped(7)
            assertThat(f.controls).hasSize(resumedControls)
            f.speechStarted(8)
            f.speechStopped(8)
            assertThat(f.events("input_audio_buffer.commit")).hasSize(1)
            f.provider("input_audio_buffer.committed", "item_id" to "fresh-resumed-input")
            assertThat(f.events("response.create")).hasSize(1)
            f.assertNoOutputDisruption()
            assertThat(f.errors).isEmpty()
        }

    @Test
    fun `late meaningful assessment and publication stay valid while held but cannot speak before resume`(): Unit =
        Fixture(withAssessment = true).use { f ->
            f.speechStarted(1)
            f.client(VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT, 1)
            f.speechStopped(1)
            f.client(VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT, 1)
            f.provider("input_audio_buffer.committed", "item_id" to "accepted")
            f.provider("conversation.item.input_audio_transcription.completed", "item_id" to "accepted", "transcript" to "네")
            val assessment = f.inputActions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Assess>().single()
            f.controller.advancePause()
            f.clearAck("pause")
            f.completeAssessment(assessment, VoiceTutorInputDecision.MEANINGFUL)
            assertThat(f.controller.canPublishInput("accepted")).isTrue()
            assertThat(f.events("response.create")).isEmpty()
            f.resume()
            assertThat(f.events("response.create")).isEmpty()
            f.controller.confirmInputPublished("unrelated")
            assertThat(f.events("response.create")).isEmpty()
            f.controller.confirmInputPublished("accepted")
            assertThat(f.events("response.create")).hasSize(1)
            f.assertNoOutputDisruption()
            assertThat(f.errors).isEmpty()
        }

    @Test
    fun `filler classification keeps exact USER deletion ACK and never invents a resumed answer`(): Unit =
        Fixture(withAssessment = true).use { f ->
            f.speechStarted(1)
            f.client(VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT, 1)
            f.speechStopped(1)
            f.client(VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT, 1)
            f.provider("input_audio_buffer.committed", "item_id" to "filler")
            f.provider("conversation.item.input_audio_transcription.completed", "item_id" to "filler", "transcript" to "음…")
            val assessment = f.inputActions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Assess>().single()
            f.controller.advancePause()
            f.clearAck("pause")
            f.completeAssessment(assessment, VoiceTutorInputDecision.NON_COMMUNICATIVE)
            assertThat(f.events("conversation.item.delete").map { it.path("item_id").asText() }).containsExactly("filler")
            f.resume()
            f.provider("conversation.item.deleted", "item_id" to "unrelated")
            assertThat(f.events("response.create")).isEmpty()
            f.provider("conversation.item.deleted", "item_id" to "filler")
            assertThat(f.events("response.create")).isEmpty()
            f.assertNoOutputDisruption()
            assertThat(f.errors).isEmpty()
        }

    @Test
    fun `MCP result must finish its exact ACK before resuming the queued continuation`(): Unit = Fixture(withTools = true).use { f ->
        val response = f.startTutorResponse()
        f.requestPause()
        val call = mapOf(
            "type" to "function_call", "status" to "completed", "call_id" to "tool-call",
            "name" to "list_studies", "arguments" to "{}",
        )
        f.response("response.done", "tutor", response, output = listOf(call))
        assertThat(f.toolCalls).hasSize(1)
        f.controller.advancePause()
        f.clearAck("pause")
        f.assertAcknowledgement(1, paused = true)
        assertThat(f.controller.beginToolExecution("tool-call")).isTrue()
        assertThat(f.controller.completeToolExecution("tool-call", VoiceTutorMcpToolResult("{}", false))).isTrue()
        val output = f.events("conversation.item.create").single().path("item")
        f.resume()
        assertThat(f.events("response.create")).hasSize(1)
        f.provider("conversation.item.added", "item" to mapOf(
            "id" to "wrong", "type" to "function_call_output", "call_id" to "tool-call", "status" to "completed",
        ))
        assertThat(f.events("response.create")).hasSize(1)
        f.provider("conversation.item.added", "item" to output)
        assertThat(f.events("response.create")).hasSize(2)
        f.provider("conversation.item.added", "item" to output)
        assertThat(f.events("response.create")).hasSize(2)
        f.assertNoOutputDisruption()
        assertThat(f.errors).isEmpty()
    }

    @Test
    fun `ordinary learner overlap still waits for the same tutor sentence and never enters pause`(): Unit = Fixture().use { f ->
        val first = f.startTutorResponse()
        f.speechStarted(2)
        f.speechStopped(2)
        f.provider("input_audio_buffer.committed", "item_id" to "overlap")
        f.controller.advancePause()
        assertThat(f.events("input_audio_buffer.clear")).isEmpty()
        assertThat(f.acknowledgements).isEmpty()
        assertThat(f.events("response.create")).hasSize(1)
        f.response("response.done", "tutor", first)
        assertThat(f.events("response.create")).hasSize(1)
        f.provider("output_audio_buffer.stopped", "response_id" to "tutor")
        assertThat(f.events("response.create")).hasSize(2)
        assertThat(f.events("input_audio_buffer.clear")).isEmpty()
        f.assertNoOutputDisruption()
    }

    @Test
    fun `pause deadline fails explicitly and closes pending state without pretending success`(): Unit = Fixture().use { f ->
        f.requestPause()
        assertThat(f.events("input_audio_buffer.clear")).hasSize(1)
        f.now.set(Duration.ofSeconds(5).toNanos())
        f.controller.advancePause()
        assertThat(f.errors).anyMatch { it is VoiceTutorPauseAcknowledgementTimeoutException }
        assertThat(f.acknowledgements).isEmpty()
        assertThat(f.controller.acceptsInputEvents()).isFalse()
        val controls = f.controls.size
        f.clearAck("late")
        f.client(VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT, 2)
        assertThat(f.controls).hasSize(controls)
        f.assertNoOutputDisruption()
    }

    @Test
    fun `closing a held call prevents all pause timers and late acknowledgements from reviving it`(): Unit = Fixture().use { f ->
        f.pause()
        val controls = f.controls.size
        val acknowledgements = f.acknowledgements.size
        f.controller.close()
        f.now.set(Duration.ofHours(1).toNanos())
        f.controller.advancePause()
        f.client(VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT, 2)
        f.clearAck("late")
        f.speechStarted(1)
        f.speechStopped(1)
        assertThat(f.controls).hasSize(controls)
        assertThat(f.acknowledgements).hasSize(acknowledgements)
        assertThat(f.controller.acceptsInputEvents()).isFalse()
    }

    private class Fixture(withAssessment: Boolean = false, withTools: Boolean = false) : AutoCloseable {
        val now = AtomicLong()
        val controls = CopyOnWriteArrayList<JsonNode>()
        val acknowledgements = CopyOnWriteArrayList<JsonNode>()
        val inputActions = CopyOnWriteArrayList<VoiceTutorInputTurnCoordinator.Action>()
        val toolCalls = CopyOnWriteArrayList<VoiceTutorMcpCall>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(12),
            responseTimeout = Duration.ofSeconds(60),
            nanoTime = now::get,
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            inputCoordinator = if (withAssessment) VoiceTutorInputTurnCoordinator() else null,
            toolsEnabled = withTools,
        )
        private val subscriptions = listOf(
            controller.providerEvents().subscribe({ controls.add(mapper.readTree(it)) }, errors::add),
            controller.clientEvents().subscribe({ acknowledgements.add(mapper.readTree(it)) }, errors::add),
            controller.inputActions().subscribe(inputActions::add, errors::add),
            controller.toolActions().subscribe(toolCalls::add, errors::add),
        )

        init {
            controller.startOpeningResponse()
            val opening = events("response.create").single()
            response("response.created", "opening", opening)
            response("response.done", "opening", opening)
            provider("output_audio_buffer.stopped", "response_id" to "opening")
            controls.clear()
        }

        fun client(type: String, sequence: Long) {
            controller.observeClientEvent(mapper.writeValueAsString(mapOf("type" to type, "sequence" to sequence)))
        }

        fun speechStarted(sequence: Long) = client(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, sequence)
        fun speechStopped(sequence: Long) = client(VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT, sequence)

        fun provider(type: String, vararg fields: Pair<String, Any?>) {
            controller.observeProviderEvent(mapper.writeValueAsString(mapOf("type" to type, *fields)))
        }

        fun response(type: String, id: String, control: JsonNode, output: List<Any> = emptyList()) {
            provider(type, "response" to mapOf(
                "id" to id,
                "status" to if (type == "response.created") "in_progress" else "completed",
                "metadata" to mapOf(
                    VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to control.path("event_id").asText(),
                ),
                "output" to output,
            ))
        }

        fun startTutorResponse(): JsonNode {
            speechStarted(1)
            speechStopped(1)
            provider("input_audio_buffer.committed", "item_id" to "first-answer")
            val creation = events("response.create").single()
            response("response.created", "tutor", creation)
            return creation
        }

        fun requestPause() {
            client(VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT, 1)
            client(VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT, 1)
            controller.advancePause()
        }

        fun pause() {
            requestPause()
            clearAck("pause")
            assertAcknowledgement(1, paused = true)
        }

        fun resume() {
            client(VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT, 2)
            clearAck("resume")
            assertAcknowledgement(2, paused = false)
        }

        fun clearAck(eventId: String) = provider("input_audio_buffer.cleared", "event_id" to eventId)

        fun completeAssessment(action: VoiceTutorInputTurnCoordinator.Action.Assess, decision: VoiceTutorInputDecision) {
            controller.completeInputAssessment(action.token, Result.success(VoiceTutorInputAssessmentResult(
                action.utterances.map { VoiceTutorInputItemAssessment(it.itemId, decision) },
            )))
        }

        fun events(type: String) = controls.filter { it.path("type").asText() == type }

        fun assertAcknowledgement(sequence: Long, paused: Boolean) {
            val event = acknowledgements.last()
            assertThat(event.path("type").asText()).isEqualTo(VoiceTutorRealtimeContract.PAUSE_STATE_EVENT)
            assertThat(event.path("sequence").asLong()).isEqualTo(sequence)
            assertThat(event.path("paused").asBoolean()).isEqualTo(paused)
        }

        fun assertNoOutputDisruption() {
            assertThat(controls.map { it.path("type").asText() })
                .doesNotContain("response.cancel", "output_audio_buffer.clear", "conversation.item.truncate")
        }

        override fun close() {
            controller.close()
            subscriptions.forEach { it.dispose() }
        }
    }

    private companion object { val mapper = JsonMapperProvider.mapper }
}
