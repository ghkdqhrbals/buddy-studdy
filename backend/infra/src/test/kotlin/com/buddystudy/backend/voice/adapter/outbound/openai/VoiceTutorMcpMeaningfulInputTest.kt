package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CompletableDeferred
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Real coordinators and input worker, with only synthetic provider events and suspended mocks. */
class VoiceTutorMcpMeaningfulInputTest {
    @Test
    fun `early tool ACK cannot bypass contextual classification or accepted transcript persistence`() =
        Fixture(VoiceTutorInputDecision.MEANINGFUL).use { f ->
            f.overlappingUtterance("응")
            f.finishToolResponseAndAcknowledgeOutput()

            assertThat(f.classifierEntered.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(f.responses()).hasSize(2)
            assertThat(f.overlapPublications()).isEmpty()
            assertThat(f.assessmentRequests.single().userId).isEqualTo(7)
            assertThat(f.assessmentRequests.single().language).isEqualTo("ko")
            assertThat(f.assessmentRequests.single().teacherContext).isEqualTo(OPENING_TEXT)
            assertThat(f.assessmentRequests.single().utterances.single().transcript).isEqualTo("응")

            f.classifierRelease.complete(Unit)
            assertThat(f.persistenceEntered.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(f.controller.canPublishInput(OVERLAP_ITEM)).isTrue()
            assertThat(f.responses()).hasSize(2)
            assertThat(f.overlapPublications().single().path("transcript").asText()).isEqualTo("응")
            f.controller.confirmInputPublished("unrelated-item")
            assertThat(f.responses()).hasSize(2)

            f.persistenceRelease.complete(Unit)
            assertThat(f.continuationCreated.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(f.responses()).hasSize(3)
            assertThat(f.controller.canPublishInput(OVERLAP_ITEM)).isFalse()
            f.acknowledgeToolOutput()
            f.controller.confirmInputPublished(OVERLAP_ITEM)
            f.transcript(OVERLAP_ITEM, "응")
            assertThat(f.responses()).hasSize(3)
            assertThat(f.overlapPublications()).hasSize(1)
            assertThat(f.events("conversation.item.delete")).isEmpty()
            f.assertNoMediaDisruptionOrFailures()
        }

    @Test
    fun `early tool ACK cannot resume until the exact rejected learner item deletion is acknowledged`() =
        Fixture(VoiceTutorInputDecision.NON_COMMUNICATIVE).use { f ->
            f.overlappingUtterance("음…")
            f.finishToolResponseAndAcknowledgeOutput()

            assertThat(f.classifierEntered.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(f.responses()).hasSize(2)
            f.classifierRelease.complete(Unit)
            assertThat(f.deletionRequested.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(f.responses()).hasSize(2)
            assertThat(f.events("conversation.item.delete").map { it.path("item_id").asText() })
                .containsExactly(OVERLAP_ITEM)
            assertThat(f.overlapPublications()).isEmpty()
            assertThat(f.controller.canPublishInput(OVERLAP_ITEM)).isFalse()

            f.provider("conversation.item.deleted", "item_id" to "unrelated-item")
            f.acknowledgeToolOutput()
            assertThat(f.responses()).hasSize(2)
            f.provider("conversation.item.deleted", "item_id" to OVERLAP_ITEM)
            assertThat(f.continuationCreated.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(f.responses()).hasSize(3)
            f.provider("conversation.item.deleted", "item_id" to OVERLAP_ITEM)
            f.acknowledgeToolOutput()
            assertThat(f.responses()).hasSize(3)
            assertThat(f.overlapPublications()).isEmpty()
            f.assertNoMediaDisruptionOrFailures()
        }

    private class Fixture(private val overlapDecision: VoiceTutorInputDecision) : AutoCloseable {
        private val now = AtomicLong()
        private val controls = CopyOnWriteArrayList<String>()
        private val toolCalls = CopyOnWriteArrayList<VoiceTutorMcpCall>()
        private val publications = CopyOnWriteArrayList<String>()
        private val errors = CopyOnWriteArrayList<Throwable>()
        private val firstLearnerResponseCreated = CountDownLatch(1)
        val continuationCreated = CountDownLatch(1)
        val classifierEntered = CountDownLatch(1)
        val persistenceEntered = CountDownLatch(1)
        val deletionRequested = CountDownLatch(1)
        val classifierRelease = CompletableDeferred<Unit>()
        val persistenceRelease = CompletableDeferred<Unit>()
        val assessmentRequests = CopyOnWriteArrayList<VoiceTutorInputAssessmentRequest>()
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            nanoTime = now::get,
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            inputCoordinator = VoiceTutorInputTurnCoordinator(),
            toolsEnabled = true,
        )
        private val controlSubscription = controller.providerEvents().subscribe({ raw ->
            controls += raw
            val event = mapper.readTree(raw)
            when (event.path("type").asText()) {
                "response.create" -> when (responses().size) {
                    2 -> firstLearnerResponseCreated.countDown()
                    3 -> continuationCreated.countDown()
                }
                "conversation.item.delete" -> if (event.path("item_id").asText() == OVERLAP_ITEM) {
                    deletionRequested.countDown()
                }
            }
        }, errors::add)
        private val toolSubscription = controller.toolActions().subscribe(toolCalls::add, errors::add)
        private val inputWorker = voiceTutorInputAssessmentRelay(
            controller = controller,
            userId = 7,
            language = "ko",
            assessment = object : VoiceTutorInputAssessmentUseCase {
                override suspend fun assess(input: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult {
                    val initial = input.utterances.single().itemId == INITIAL_ITEM
                    if (!initial) {
                        assertThat(input.utterances.single().itemId).isEqualTo(OVERLAP_ITEM)
                        assessmentRequests += input
                        classifierEntered.countDown()
                        classifierRelease.await()
                    }
                    return VoiceTutorInputAssessmentResult(input.utterances.map {
                        VoiceTutorInputItemAssessment(
                            it.itemId,
                            if (initial) VoiceTutorInputDecision.MEANINGFUL else overlapDecision,
                        )
                    })
                }
            },
            onProviderEvent = { raw, persist, forward ->
                assertThat(persist).isTrue()
                assertThat(forward).isTrue()
                val event = mapper.readTree(raw)
                assertThat(event.path("type").asText())
                    .isEqualTo("conversation.item.input_audio_transcription.completed")
                publications += raw
                if (event.path("item_id").asText() == OVERLAP_ITEM) {
                    persistenceEntered.countDown()
                    persistenceRelease.await()
                }
            },
        ).subscribe({}, errors::add)
        private val toolResponseToken: String

        init {
            try {
                controller.startOpeningResponse()
                val openingToken = responses().single().path("event_id").asText()
                response("response.created", "opening", openingToken, "in_progress")
                response("response.done", "opening", openingToken, "completed", listOf(
                    mapOf("type" to "message", "role" to "assistant", "content" to listOf(
                        mapOf("type" to "output_audio", "transcript" to OPENING_TEXT),
                    )),
                ))
                provider("output_audio_buffer.stopped", "response_id" to "opening")
                utterance(1, INITIAL_ITEM, "내 하위 주제를 조회해 줘")
                assertThat(firstLearnerResponseCreated.await(2, TimeUnit.SECONDS)).isTrue()
                toolResponseToken = responses().last().path("event_id").asText()
                response("response.created", TOOL_RESPONSE, toolResponseToken, "in_progress")
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        fun overlappingUtterance(text: String) {
            utterance(2, OVERLAP_ITEM, text)
            // The function response is still active: no speculative classification
            // before its response.done establishes a completed teacher context.
            assertThat(assessmentRequests).isEmpty()
            assertThat(responses()).hasSize(2)
        }

        fun finishToolResponseAndAcknowledgeOutput() {
            response("response.done", TOOL_RESPONSE, toolResponseToken, "completed", listOf(mapOf(
                "type" to "function_call",
                "status" to "completed",
                "call_id" to TOOL_CALL,
                "name" to "list_studies",
                "arguments" to "{\"parent_study_id\":101,\"limit\":20}",
            )))
            assertThat(toolCalls.map { it.callId }).containsExactly(TOOL_CALL)
            assertThat(controller.beginToolExecution(TOOL_CALL)).isTrue()
            assertThat(controller.completeToolExecution(
                TOOL_CALL,
                VoiceTutorMcpToolResult("{\"studies\":[],\"totalCount\":0}", isError = false),
            )).isTrue()
            acknowledgeToolOutput()
            // There is no output_audio_buffer.stopped for this function-only
            // response. Its exact output ACK is complete; input gates remain.
            assertThat(events("conversation.item.create")).hasSize(1)
            assertThat(responses()).hasSize(2)
        }

        fun acknowledgeToolOutput() {
            val item = events("conversation.item.create").single().path("item").deepCopy<ObjectNode>()
            item.put("status", "completed")
            provider("conversation.item.added", "item" to item)
        }

        private fun utterance(sequence: Long, itemId: String, text: String) {
            speech(true, sequence)
            now.addAndGet(Duration.ofSeconds(1).toNanos())
            speech(false, sequence)
            provider("input_audio_buffer.committed", "item_id" to itemId)
            transcript(itemId, text)
        }

        private fun speech(start: Boolean, sequence: Long) {
            controller.observeClientEvent(mapper.writeValueAsString(mapOf(
                "type" to if (start) VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT
                    else VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT,
                "sequence" to sequence,
            )))
        }

        fun transcript(itemId: String, text: String) = provider(
            "conversation.item.input_audio_transcription.completed", "item_id" to itemId, "transcript" to text,
        )

        fun provider(type: String, vararg fields: Pair<String, Any>) =
            controller.observeProviderEvent(mapper.writeValueAsString(mapOf("type" to type, *fields)))

        private fun response(
            type: String,
            id: String,
            token: String,
            status: String,
            output: List<Map<String, Any>> = emptyList(),
        ) = provider(type, "response" to mapOf(
            "id" to id,
            "status" to status,
            "output" to output,
            "metadata" to mapOf(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to token),
        ))

        fun events(type: String): List<JsonNode> = controls.map(mapper::readTree).filter { it.path("type").asText() == type }
        fun responses(): List<JsonNode> = events("response.create")
        fun overlapPublications(): List<JsonNode> = publications.map(mapper::readTree)
            .filter { it.path("item_id").asText() == OVERLAP_ITEM }

        fun assertNoMediaDisruptionOrFailures() {
            assertThat(controls.map { mapper.readTree(it).path("type").asText() }).doesNotContain(
                "response.cancel", "output_audio_buffer.clear", "input_audio_buffer.clear", "conversation.item.truncate",
            )
            assertThat(errors).isEmpty()
            assertThat(controller.acceptsInputEvents()).isTrue()
        }

        override fun close() {
            controller.close()
            inputWorker.dispose()
            toolSubscription.dispose()
            controlSubscription.dispose()
            classifierRelease.cancel()
            persistenceRelease.cancel()
        }
    }

    private companion object {
        val mapper = JsonMapperProvider.mapper
        const val INITIAL_ITEM = "initial-request"
        const val OVERLAP_ITEM = "overlap-learner"
        const val TOOL_RESPONSE = "tool-response"
        const val TOOL_CALL = "read-children"
        const val OPENING_TEXT = "학습을 시작할 준비가 됐나요?"
    }
}
