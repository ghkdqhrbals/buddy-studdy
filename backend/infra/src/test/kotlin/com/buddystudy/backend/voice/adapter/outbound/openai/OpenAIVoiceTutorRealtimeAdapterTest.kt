package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest.dynamicTest
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class OpenAIVoiceTutorRealtimeAdapterTest {
    private val mapper = JsonMapperProvider.mapper
    private val unavailableAssessment = object : VoiceTutorInputAssessmentUseCase {
        override suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult =
            error("This adapter unit test must never invoke input assessment.")
    }
    private val preparedProviderEvents = mutableMapOf<VoiceTutorDuplexTurnController, Flux<String>>()
    private val controllerTransports = mutableMapOf<VoiceTutorDuplexTurnController, VoiceTutorRealtimeTransport>()
    private val clientSpeechSequences = mutableMapOf<VoiceTutorDuplexTurnController, Long>()

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
        val adapter = OpenAIVoiceTutorRealtimeAdapter(BuddyStudyProperties(), unavailableAssessment)
        val update = mapper.readTree(
            adapter.sessionUpdate(
                VoiceTutorRealtimeRequest(
                    userId = 1,
                    model = "gpt-realtime-test",
                    voice = "marin",
                    instructions = "Tutor safely.",
                    language = "ko",
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
    fun `legacy provider transcript cannot self authorize spoken end without semantic input assessment`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.LEGACY_PCM_RELAY)
        val lifecycle = mutableListOf<String>()
        val subscription = controller.serverLifecycleEvents().subscribe(lifecycle::add)
        try {
            learnerTurn(controller)
            val disposition = controller.observeProviderEvent(mapper.writeValueAsString(mapOf(
                "type" to "conversation.item.input_audio_transcription.completed",
                "item_id" to "legacy-user-item",
                "transcript" to "학습 끝낼게",
            )))

            assertThat(disposition).isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST)
            assertThat(lifecycle).isEmpty()
            assertThat(controller.acceptsInputEvents()).isTrue()
        } finally {
            controller.close()
            subscription.dispose()
        }
    }

    @Test
    fun `production legacy turn path assesses and persists the exact server VAD item before spoken end`() {
        val assessments = CopyOnWriteArrayList<VoiceTutorInputAssessmentRequest>()
        val persisted = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val lifecycle = CopyOnWriteArrayList<String>()
        val ended = CountDownLatch(1)
        val assessment = object : VoiceTutorInputAssessmentUseCase {
            override suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult {
                assessments += request
                return VoiceTutorInputAssessmentResult(
                    request.utterances.map {
                        VoiceTutorInputItemAssessment(
                            itemId = it.itemId,
                            decision = VoiceTutorInputDecision.MEANINGFUL,
                            intent = VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON,
                        )
                    },
                )
            }
        }
        val adapter = OpenAIVoiceTutorRealtimeAdapter(BuddyStudyProperties(), assessment)
        val request = VoiceTutorRealtimeRequest(
            userId = 77,
            model = "gpt-realtime-test",
            voice = "marin",
            instructions = "Tutor safely.",
            language = "ko",
        )
        val controller = adapter.createLegacyTurnController()
        val controls = CopyOnWriteArrayList<String>()
        val controlSubscription = controller.providerEvents().subscribe(controls::add, errors::add)
        val lifecycleSubscription = controller.serverLifecycleEvents().subscribe({ raw ->
            lifecycle += raw
            ended.countDown()
        }, errors::add)
        val inputWorker = adapter.legacyInputAssessmentRelay(controller, request) { raw, persist, forward ->
            assertThat(persist).isTrue()
            assertThat(forward).isTrue()
            persisted += raw
            true
        }.subscribe({}, errors::add)
        try {
            controller.startOpeningResponse()
            val openingControl = controls.single()
            controller.observeProviderEvent(responseEvent("response.created", "legacy-opening", openingControl))
            controller.observeProviderEvent(responseEvent("response.done", "legacy-opening", openingControl))

            val itemId = "legacy-user-item"
            assertThat(controller.observeProviderEvent(mapper.writeValueAsString(mapOf(
                "type" to "input_audio_buffer.speech_started", "item_id" to itemId,
            )))).isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST)
            assertThat(controller.observeProviderEvent(mapper.writeValueAsString(mapOf(
                "type" to "input_audio_buffer.speech_stopped", "item_id" to itemId,
            )))).isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST)
            assertThat(controller.observeProviderEvent(mapper.writeValueAsString(mapOf(
                "type" to "input_audio_buffer.committed", "item_id" to itemId,
            )))).isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST)
            val transcript = mapper.writeValueAsString(mapOf(
                "type" to "conversation.item.input_audio_transcription.completed",
                "item_id" to itemId,
                "transcript" to "학습 종료할게",
            ))
            assertThat(controller.observeProviderEvent(transcript)).isEqualTo(VoiceTutorProviderRelayDisposition.DROP)

            assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(errors).isEmpty()
            assertThat(assessments).hasSize(1)
            assertThat(assessments.single().userId).isEqualTo(77)
            assertThat(assessments.single().language).isEqualTo("ko")
            assertThat(assessments.single().utterances.single().itemId).isEqualTo(itemId)
            assertThat(persisted).hasSize(1)
            val persistedTranscript = mapper.readTree(persisted.single())
            assertThat(persistedTranscript.path("type").asText())
                .isEqualTo("conversation.item.input_audio_transcription.completed")
            assertThat(persistedTranscript.path("item_id").asText()).isEqualTo(itemId)
            assertThat(persistedTranscript.path("transcript").asText()).isEqualTo("학습 종료할게")
            assertThat(persistedTranscript.path(VoiceTutorTranscriptMetadata.LESSON_REVISION).asLong()).isZero()
            assertThat(lifecycle.map { mapper.readTree(it).path("type").asText() })
                .containsExactly(VoiceTutorRealtimeContract.SPOKEN_LESSON_END_EVENT)
            assertThat(controls.map { mapper.readTree(it).path("type").asText() })
                .containsExactly("response.create")
            assertThat(controller.acceptsInputEvents()).isFalse()
        } finally {
            controller.close()
            inputWorker.dispose()
            lifecycleSubscription.dispose()
            controlSubscription.dispose()
        }
    }

    @TestFactory
    fun `teacher opening is proactive and idempotent for each transport`() =
        VoiceTutorRealtimeTransport.entries.map { transport ->
            dynamicTest(transport.name) {
                val controller = controller(transport = transport, readyForLearnerTurns = false)
                val openingControl = AtomicReference<String>()

                StepVerifier.create(providerEvents(controller))
                    .then { controller.startOpeningResponse() }
                    .assertNext { raw ->
                        openingControl.set(raw)
                        val node = mapper.readTree(raw)
                        assertThat(node.path("type").asText()).isEqualTo("response.create")
                        assertThat(node.path("event_id").asText())
                            .startsWith("buddystudy-internal-duplex-opening-response-")
                        assertThat(node.path("response").has("instructions")).isFalse()
                        assertThat(
                            node.path("response").path("metadata")
                                .path(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY).asText(),
                        ).isEqualTo(node.path("event_id").asText())
                    }
                    .then { controller.startOpeningResponse() }
                    .expectNoEvent(Duration.ofMillis(10))
                    .then {
                        controller.observeProviderEvent(
                            responseEvent("response.created", "opening", openingControl.get()),
                        )
                        controller.observeProviderEvent(
                            responseEvent("response.done", "opening", openingControl.get()),
                        )
                        if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "opening"))
                        }
                        controller.startOpeningResponse()
                    }
                    .expectNoEvent(Duration.ofMillis(10))
                    .then { controller.close() }
                    .verifyComplete()
            }
        }

    @Test
    fun `legacy opening waits for session configuration acknowledgement`() {
        val controller = controller(readyForLearnerTurns = false)

        StepVerifier.create(providerEvents(controller))
            .then { controller.observeProviderEvent(event("session.created")) }
            .expectNoEvent(Duration.ofMillis(10))
            .then { controller.observeProviderEvent(event("session.updated")) }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("event_id").asText())
                    .startsWith("buddystudy-internal-duplex-opening-response-")
            }
            .then { controller.observeProviderEvent(event("session.updated")) }
            .expectNoEvent(Duration.ofMillis(10))
            .then { controller.close() }
            .verifyComplete()
    }

    @Test
    fun `opening preserves an early learner commit until its complete sentence drains`() {
        val controller = controller(
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            readyForLearnerTurns = false,
        )
        val openingControl = AtomicReference<String>()

        StepVerifier.create(providerEvents(controller).take(2))
            .then {
                controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                controller.observeProviderEvent(committedEvent("unsolicited-before-stop"))
                controller.startOpeningResponse()
            }
            .expectNoEvent(Duration.ofMillis(10))
            .then {
                controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))
                controller.observeProviderEvent(committedEvent("early-learner"))
            }
            .assertNext { raw ->
                openingControl.set(raw)
                assertThat(mapper.readTree(raw).path("event_id").asText())
                    .startsWith("buddystudy-internal-duplex-opening-response-")
            }
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "opening", openingControl.get()))
                controller.observeProviderEvent(responseEvent("response.done", "opening", openingControl.get()))
            }
            .expectNoEvent(Duration.ofMillis(10))
            .then { controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "opening")) }
            .assertNext { raw ->
                val node = mapper.readTree(raw)
                assertThat(node.path("type").asText()).isEqualTo("response.create")
                assertThat(node.path("event_id").asText())
                    .startsWith("buddystudy-internal-duplex-turn-response-")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `fully committed learner greeting before readiness stays queued behind the opening`() {
        val controller = controller(
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            readyForLearnerTurns = false,
        )
        val openingControl = AtomicReference<String>()

        StepVerifier.create(providerEvents(controller).take(2))
            .then { learnerTurn(controller) }
            .expectNoEvent(Duration.ofMillis(10))
            .then { controller.startOpeningResponse() }
            .assertNext { raw ->
                openingControl.set(raw)
                assertThat(mapper.readTree(raw).path("event_id").asText())
                    .startsWith("buddystudy-internal-duplex-opening-response-")
            }
            .then {
                controller.observeProviderEvent(responseEvent("response.created", "opening", openingControl.get()))
                controller.observeProviderEvent(responseEvent("response.done", "opening", openingControl.get()))
            }
            .expectNoEvent(Duration.ofMillis(10))
            .then { controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "opening")) }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("event_id").asText())
                    .startsWith("buddystudy-internal-duplex-turn-response-")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `continuous speech cannot take the floor before readiness or the pending opening`() {
        val controller = controller(
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            readyForLearnerTurns = false,
        )

        StepVerifier.create(providerEvents(controller).take(1))
            .then {
                controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                controller.fireContinuousSpeechDeadline()
            }
            .expectNoEvent(Duration.ofMillis(10))
            .then {
                controller.startOpeningResponse()
                controller.fireContinuousSpeechDeadline()
            }
            .expectNoEvent(Duration.ofMillis(10))
            .then {
                controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))
                controller.observeProviderEvent(committedEvent("early-learner"))
            }
            .assertNext { raw ->
                val node = mapper.readTree(raw)
                assertThat(node.path("event_id").asText())
                    .startsWith("buddystudy-internal-duplex-opening-response-")
                assertThat(node.path("response").has("instructions")).isFalse()
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `ordinary user turn creates exactly one server owned response after commit`() {
        val controller = controller()

        StepVerifier.create(providerEvents(controller).take(1))
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

        StepVerifier.create(providerEvents(controller).take(2))
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

        StepVerifier.create(providerEvents(controller).take(2))
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

        StepVerifier.create(providerEvents(controller).take(2))
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

        StepVerifier.create(providerEvents(controller).take(2))
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

        StepVerifier.create(providerEvents(controller).take(2))
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

        StepVerifier.create(providerEvents(controller).take(2))
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

        StepVerifier.create(providerEvents(controller).take(2))
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

        StepVerifier.create(providerEvents(controller).take(2))
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

        StepVerifier.create(providerEvents(controller))
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

        StepVerifier.create(providerEvents(controller))
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
                controller.fireResponseTimeout(responseGeneration = 2, createEventId = firstEventId)
            }
            .expectNoEvent(Duration.ofMillis(25))
            .then {
                val secondEventId = mapper.readTree(secondControl.get()).path("event_id").asText()
                controller.fireResponseTimeout(responseGeneration = 3, createEventId = secondEventId)
            }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.cancel")
            }
            .expectError(VoiceTutorProviderResponseTimeoutException::class.java)
            .verify()
    }

    @Test
    fun `legacy long speech waits for its natural stop and commit without intervention`(): Unit {
        val controller = controller()

        StepVerifier.create(providerEvents(controller).take(1))
            .then {
                controller.observeProviderEvent(event("input_audio_buffer.speech_started"))
                controller.fireContinuousSpeechDeadline()
                controller.fireContinuousSpeechDeadline()
            }
            .expectNoEvent(Duration.ofMillis(10))
            .then { controller.observeProviderEvent(event("input_audio_buffer.speech_stopped")) }
            .expectNoEvent(Duration.ofMillis(10))
            .then { controller.observeProviderEvent(event("input_audio_buffer.committed")) }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
                assertThat(raw).doesNotContain("continuous_intervention", "response.cancel", "output_audio_buffer.clear")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `legacy elapsed speech checkpoint cannot take the floor after current tutor sentence`(): Unit {
        val controller = controller()
        val activeControl = AtomicReference<String>()

        StepVerifier.create(providerEvents(controller).take(2))
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
            .expectNoEvent(Duration.ofMillis(10))
            .then {
                controller.observeProviderEvent(event("input_audio_buffer.speech_stopped"))
                controller.observeProviderEvent(event("input_audio_buffer.committed"))
            }
            .assertNext { raw ->
                val node = mapper.readTree(raw)
                assertThat(node.path("type").asText()).isEqualTo("response.create")
                assertThat(raw).doesNotContain("continuous_intervention", "response.cancel", "output_audio_buffer.clear")
            }
            .verifyComplete()

        controller.close()
    }

    @TestFactory
    fun `webrtc releases a queued turn only after both server completion signals in any order`() =
        listOf(
            listOf("done", "provider-stopped"),
            listOf("provider-stopped", "done"),
        ).map { ordering ->
            dynamicTest(ordering.joinToString(" -> ")) {
                val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
                val activeControl = AtomicReference<String>()

                StepVerifier.create(providerEvents(controller).take(2))
                    .then { queueOrdinaryResponse(controller) }
                    .assertNext(activeControl::set)
                    .then {
                        controller.observeProviderEvent(
                            responseEvent("response.created", "response-webrtc", activeControl.get()),
                        )
                        learnerTurn(controller)
                        applyWebRtcGateSignal(controller, activeControl.get(), ordering[0])
                    }
                    .expectNoEvent(Duration.ofMillis(10))
                    .then { applyWebRtcGateSignal(controller, activeControl.get(), ordering[1]) }
                    .assertNext { raw ->
                        assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
                    }
                    .verifyComplete()

                controller.close()
            }
        }

    @Test
    fun `webrtc stale provider ids and even matching client drain cannot release current response`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        val activeControl = AtomicReference<String>()

        StepVerifier.create(providerEvents(controller).take(2))
            .then { queueOrdinaryResponse(controller) }
            .assertNext(activeControl::set)
            .then {
                controller.observeProviderEvent(
                    responseEvent("response.created", "response-current", activeControl.get()),
                )
                learnerTurn(controller)
                controller.observeProviderEvent(
                    responseEvent("response.done", "response-current", activeControl.get()),
                )
                controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "response-stale"))
                controller.observeClientEvent(playoutDrainedEvent("response-stale"))
                controller.observeClientEvent(playoutDrainedEvent("response-current"))
            }
            .expectNoEvent(Duration.ofMillis(20))
            .then {
                controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "response-current"))
            }
            .assertNext { raw ->
                assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
            }
            .verifyComplete()

        controller.close()
    }

    @Test
    fun `webrtc never cancels truncates or clears when learner starts over tutor`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        val activeControl = AtomicReference<String>()

        StepVerifier.create(providerEvents(controller))
            .then { queueOrdinaryResponse(controller) }
            .assertNext(activeControl::set)
            .then {
                controller.observeProviderEvent(
                    responseEvent("response.created", "response-speaking", activeControl.get()),
                )
                controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 2))
            }
            .expectNoEvent(Duration.ofMillis(25))
            .thenCancel()
            .verify()

        controller.close()
    }

    @Test
    fun `manual webrtc completes opening and four learner turns without any device playout acknowledgement`() {
        val controller = controller(
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            readyForLearnerTurns = false,
        )
        val emitted = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val subscription = providerEvents(controller, includeInputCommits = true)
            .subscribe(emitted::add, errors::add)
        fun responses() = emitted.filter { mapper.readTree(it).path("type").asText() == "response.create" }
        try {
            controller.startOpeningResponse()
            val opening = responses().single()
            controller.observeProviderEvent(responseEvent("response.created", "opening", opening))
            controller.observeProviderEvent(responseEvent("response.done", "opening", opening))
            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "opening"))
            assertThat(responses()).hasSize(1)

            // Mirrors the reported live sequence: the opening has fully left
            // the provider, then the learner speaks. No device ACK is invented
            // by this fixture, and every later response repeats the real gates.
            for (sequence in 1L..4L) {
                controller.observeClientEvent(clientSpeechEvent(started = true, sequence = sequence))
                controller.observeClientEvent(clientSpeechEvent(started = false, sequence = sequence))
                assertServerOwnedInputCommit(emitted.last())
                assertThat(responses()).hasSize(sequence.toInt())
                assertThat(controller.observeProviderEvent(committedEvent("learner-$sequence")))
                    .isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST)
                assertThat(controller.observeProviderEvent(
                    """{"type":"conversation.item.input_audio_transcription.completed","item_id":"learner-$sequence","transcript":"synthetic learner turn $sequence"}""",
                )).isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST)
                assertThat(responses()).hasSize(sequence.toInt() + 1)

                val responseId = "reply-$sequence"
                val control = responses().last()
                controller.observeProviderEvent(responseEvent("response.created", responseId, control))
                controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.started", responseId))
                assertThat(controller.observeProviderEvent(audioDeltaEvent(responseId)))
                    .isEqualTo(VoiceTutorProviderRelayDisposition.PERSIST_ONLY)
                controller.observeProviderEvent(responseEvent("response.done", responseId, control))
                controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", responseId))
                assertThat(responses()).hasSize(sequence.toInt() + 1)
            }

            assertThat(responses()).hasSize(5)
            assertThat(emitted.filter { mapper.readTree(it).path("type").asText() == "input_audio_buffer.commit" })
                .hasSize(4)
            assertThat(emitted.map { mapper.readTree(it).path("type").asText() })
                .containsOnly("response.create", "input_audio_buffer.commit")
            assertThat(errors).isEmpty()
        } finally {
            controller.close()
            subscription.dispose()
        }
    }

    @Test
    fun `four learner utterances queued over teacher audio survive until its server output drains without a device ack`() {
        val controller = controller(
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            readyForLearnerTurns = false,
        )
        val emitted = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val subscription = providerEvents(controller, includeInputCommits = true)
            .subscribe(emitted::add, errors::add)
        fun responses() = emitted.filter { mapper.readTree(it).path("type").asText() == "response.create" }
        try {
            controller.startOpeningResponse()
            val opening = responses().single()
            controller.observeProviderEvent(responseEvent("response.created", "opening", opening))
            controller.observeProviderEvent(responseEvent("response.done", "opening", opening))
            for (sequence in 1L..4L) {
                controller.observeClientEvent(clientSpeechEvent(started = true, sequence = sequence))
                controller.observeClientEvent(clientSpeechEvent(started = false, sequence = sequence))
                assertServerOwnedInputCommit(emitted.last())
                assertThat(controller.observeProviderEvent(committedEvent("queued-learner-$sequence")))
                    .isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST)
                assertThat(controller.observeProviderEvent(
                    """{"type":"conversation.item.input_audio_transcription.completed","item_id":"queued-learner-$sequence","transcript":"synthetic queued turn $sequence"}""",
                )).isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST)
                assertThat(responses()).hasSize(1)
            }

            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "opening"))
            assertThat(responses()).hasSize(2)
            val reply = responses().last()
            assertThat(mapper.readTree(reply).path("event_id").asText())
                .startsWith("buddystudy-internal-duplex-turn-response-")
            // All four input Items remain in the default conversation. One
            // response answers the accumulated input; no item is cleared.
            assertThat(mapper.readTree(reply).path("response").has("input")).isFalse()
            controller.observeProviderEvent(responseEvent("response.created", "queued-reply", reply))
            controller.observeProviderEvent(responseEvent("response.done", "queued-reply", reply))
            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "queued-reply"))
            assertThat(responses()).hasSize(2)
            assertThat(emitted.filter { mapper.readTree(it).path("type").asText() == "input_audio_buffer.commit" })
                .hasSize(4)
            assertThat(emitted.map { mapper.readTree(it).path("type").asText() })
                .containsOnly("response.create", "input_audio_buffer.commit")
            assertThat(errors).isEmpty()
        } finally {
            controller.close()
            subscription.dispose()
        }
    }

    @Test
    fun `webrtc stale duplicate and compatibility completion events cannot advance another response`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        val emitted = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val subscription = providerEvents(controller, includeInputCommits = true)
            .subscribe(emitted::add, errors::add)
        fun responses() = emitted.filter { mapper.readTree(it).path("type").asText() == "response.create" }
        try {
            learnerTurn(controller)
            val first = responses().single()
            controller.observeProviderEvent(responseEvent("response.created", "first", first))
            learnerTurn(controller)
            controller.observeProviderEvent(responseEvent("response.done", "first", first))
            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "first"))
            assertThat(responses()).hasSize(2)
            val second = responses().last()
            controller.observeProviderEvent(responseEvent("response.created", "second", second))
            learnerTurn(controller)

            assertThat(controller.observeProviderEvent(responseEvent("response.done", "first", first)))
                .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
            assertThat(controller.observeProviderEvent(responseEvent("response.done", "first", second)))
                .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
            assertThat(controller.observeProviderEvent(responseEvent("response.done", "second", first)))
                .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
            assertThat(controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "first")))
                .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
            listOf("first", "second", "unknown", "").forEach { responseId ->
                repeat(2) {
                    assertThat(controller.observeClientEvent(playoutDrainedEvent(responseId))).isTrue()
                    assertThat(controller.observeClientEvent(playbackCompletedEvent(responseId))).isTrue()
                }
            }
            controller.fireWebRtcPlayoutTimeout(2L, "first")
            assertThat(responses()).hasSize(2)
            assertThat(errors).isEmpty()

            // Even a matching provider stop cannot replace successful generation.
            repeat(2) {
                controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "second"))
            }
            assertThat(responses()).hasSize(2)
            controller.observeProviderEvent(responseEvent("response.done", "second", second))
            assertThat(responses()).hasSize(3)
            repeat(2) {
                controller.observeProviderEvent(responseEvent("response.done", "second", second))
                controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "second"))
                controller.observeClientEvent(playoutDrainedEvent("second"))
            }
            assertThat(responses()).hasSize(3)
            val third = responses().last()
            controller.observeProviderEvent(responseEvent("response.created", "third", third))
            controller.observeProviderEvent(responseEvent("response.done", "third", third))
            // An already scheduled timeout from the preceding response must
            // not terminate a new response that is now waiting on its own stop.
            controller.fireWebRtcPlayoutTimeout(3L, "second")
            assertThat(errors).isEmpty()
            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "third"))
            assertThat(responses()).hasSize(3)
            assertThat(emitted.map { mapper.readTree(it).path("type").asText() })
                .containsOnly("response.create", "input_audio_buffer.commit")
            assertThat(errors).isEmpty()
        } finally {
            controller.close()
            subscription.dispose()
        }
    }

    @Test
    fun `webrtc missing provider stop remains terminal even after a matching device acknowledgement`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        val activeControl = AtomicReference<String>()
        try {
            StepVerifier.withVirtualTime { providerEvents(controller) }
                .then { learnerTurn(controller) }
                .assertNext(activeControl::set)
                .then {
                    controller.observeProviderEvent(responseEvent("response.created", "missing-stop", activeControl.get()))
                    controller.observeProviderEvent(responseEvent("response.done", "missing-stop", activeControl.get()))
                    controller.observeClientEvent(playoutDrainedEvent("missing-stop"))
                    learnerTurn(controller)
                }
                .expectNoEvent(Duration.ofSeconds(59))
                .then { controller.observeClientEvent(playoutDrainedEvent("missing-stop")) }
                .thenAwait(Duration.ofSeconds(1))
                .expectError(VoiceTutorProviderPlayoutTimeoutException::class.java)
                .verify(Duration.ofSeconds(2))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `webrtc completed server output cancels its watchdog without any device acknowledgement`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        val activeControl = AtomicReference<String>()
        try {
            StepVerifier.withVirtualTime { providerEvents(controller) }
                .then { learnerTurn(controller) }
                .assertNext(activeControl::set)
                .then {
                    controller.observeProviderEvent(responseEvent("response.created", "complete", activeControl.get()))
                    controller.observeProviderEvent(responseEvent("response.done", "complete", activeControl.get()))
                }
                .expectNoEvent(Duration.ofSeconds(30))
                .then { controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "complete")) }
                .expectNoEvent(Duration.ofSeconds(61))
                .then { controller.close() }
                .verifyComplete()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `manual speech emits one reserved commit for the owned stop and waits for acknowledgement`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        try {
            StepVerifier.create(providerEvents(controller, includeInputCommits = true).take(2))
                .then {
                    // An unowned stop cannot poison the next legitimate sequence.
                    assertThat(controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 100))).isTrue()
                    assertThat(controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))).isTrue()
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                    // Out-of-order starts must not replace the currently owned utterance.
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 2))
                    controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 2))
                }
                .expectNoEvent(Duration.ofMillis(10))
                .then {
                    val stop = mapper.readTree(clientSpeechEvent(started = false, sequence = 1))
                        .deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                    stop.put("event_id", "untrusted-client-id")
                    stop.put("private", "untrusted-speech-payload")
                    assertThat(controller.observeClientEvent(stop.toString())).isTrue()
                    controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                }
                .assertNext { raw ->
                    assertServerOwnedInputCommit(raw)
                    assertThat(raw).doesNotContain("sequence", "untrusted", "speech.stopped")
                }
                .expectNoEvent(Duration.ofMillis(10))
                .then { controller.observeProviderEvent(committedEvent("manual-1")) }
                .assertNext { raw ->
                    assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
                }
                .verifyComplete()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `new speech before an older commit acknowledgement retains its own pending count`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        try {
            StepVerifier.create(providerEvents(controller, includeInputCommits = true).take(3))
                .then {
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                    controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))
                }
                .assertNext(::assertServerOwnedInputCommit)
                .then {
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 2))
                    controller.observeProviderEvent(committedEvent("manual-1"))
                    // The same provider ACK cannot finish the new live utterance.
                    assertThat(controller.observeProviderEvent(committedEvent("manual-1")))
                        .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
                    controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))
                }
                .expectNoEvent(Duration.ofMillis(10))
                .then { controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 2)) }
                .assertNext(::assertServerOwnedInputCommit)
                .then {
                    controller.observeProviderEvent(committedEvent("manual-1"))
                    controller.fireInputCommitTimeout(1)
                }
                .expectNoEvent(Duration.ofMillis(10))
                .then { controller.observeProviderEvent(committedEvent("manual-2")) }
                .assertNext { raw ->
                    assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
                }
                .verifyComplete()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `multiple stopped utterances require every outstanding commit acknowledgement`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        try {
            StepVerifier.create(providerEvents(controller, includeInputCommits = true).take(3))
                .then {
                    for (sequence in 1L..2L) {
                        controller.observeClientEvent(clientSpeechEvent(started = true, sequence = sequence))
                        controller.observeClientEvent(clientSpeechEvent(started = false, sequence = sequence))
                    }
                }
                .assertNext(::assertServerOwnedInputCommit)
                .assertNext(::assertServerOwnedInputCommit)
                .then { controller.observeProviderEvent(committedEvent("manual-1")) }
                .expectNoEvent(Duration.ofMillis(10))
                .then { controller.observeProviderEvent(committedEvent("manual-2")) }
                .assertNext { raw ->
                    assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create")
                }
                .verifyComplete()
        } finally {
            controller.close()
        }
    }

    @TestFactory
    fun `manual overlap waits for commit acknowledgement and both teacher server gates in any order`() =
        permutations(listOf("commit-ack", "done", "provider-stopped")).map { ordering ->
            dynamicTest(ordering.joinToString(" -> ")) {
                val controller = controller(
                    transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
                    readyForLearnerTurns = false,
                )
                val opening = AtomicReference<String>()
                fun applySignal(signal: String) {
                    if (signal == "commit-ack") {
                        controller.observeProviderEvent(committedEvent("overlapping-learner"))
                    } else {
                        applyWebRtcGateSignal(controller, opening.get(), signal)
                    }
                }
                try {
                    StepVerifier.create(providerEvents(controller, includeInputCommits = true).take(3))
                        .then { controller.startOpeningResponse() }
                        .assertNext(opening::set)
                        .then {
                            controller.observeProviderEvent(responseEvent("response.created", "response-webrtc", opening.get()))
                            controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                            controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))
                        }
                        .assertNext(::assertServerOwnedInputCommit)
                        .then { applySignal(ordering[0]) }
                        .expectNoEvent(Duration.ofMillis(5))
                        .then { applySignal(ordering[1]) }
                        .expectNoEvent(Duration.ofMillis(5))
                        .then { applySignal(ordering[2]) }
                        .assertNext { raw ->
                            assertThat(mapper.readTree(raw).path("event_id").asText())
                                .startsWith("buddystudy-internal-duplex-turn-response-")
                            assertThat(raw).doesNotContain("response.cancel", "output_audio_buffer.clear", "conversation.item.truncate")
                        }
                        .verifyComplete()
                } finally {
                    controller.close()
                }
            }
        }

    @Test
    fun `manual continuous speech only checkpoints audio and responds after natural stop`(): Unit {
        val now = AtomicLong()
        val controller = controller(nanoTime = now::get, transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        try {
            StepVerifier.create(providerEvents(controller, includeInputCommits = true).take(3))
                .then {
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                    controller.fireContinuousSpeechDeadline()
                }
                .assertNext(::assertServerOwnedInputCheckpoint)
                .then {
                    controller.observeProviderEvent(committedEvent("long-speech-checkpoint"))
                    controller.fireContinuousSpeechDeadline()
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                    controller.fireContinuousSpeechDeadline()
                }
                .expectNoEvent(Duration.ofMillis(10))
                .then {
                    now.set(Duration.ofSeconds(1).toNanos())
                    controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))
                }
                .assertNext(::assertServerOwnedInputCommit)
                .then { controller.observeProviderEvent(committedEvent("finished-learner")) }
                .assertNext { raw ->
                    assertThat(mapper.readTree(raw).path("event_id").asText())
                        .startsWith("buddystudy-internal-duplex-turn-response-")
                    assertThat(raw).doesNotContain("continuous_intervention", "response.cancel", "output_audio_buffer.clear")
                }
                .verifyComplete()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `manual checkpoint during tutor speech cannot turn into an intervention when tutor finishes`(): Unit {
        val controller = controller(
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            readyForLearnerTurns = false,
        )
        val opening = AtomicReference<String>()
        try {
            StepVerifier.create(providerEvents(controller, includeInputCommits = true).take(4))
                .then { controller.startOpeningResponse() }
                .assertNext(opening::set)
                .then {
                    controller.observeProviderEvent(responseEvent("response.created", "opening", opening.get()))
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                    controller.fireContinuousSpeechDeadline()
                }
                .assertNext(::assertServerOwnedInputCheckpoint)
                .then {
                    controller.observeProviderEvent(committedEvent("still-speaking"))
                    controller.observeProviderEvent(responseEvent("response.done", "opening", opening.get()))
                }
                .expectNoEvent(Duration.ofMillis(10))
                .then { controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "opening")) }
                .expectNoEvent(Duration.ofMillis(10))
                .then { controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1)) }
                .assertNext(::assertServerOwnedInputCommit)
                .then { controller.observeProviderEvent(committedEvent("learner-tail")) }
                .assertNext { raw ->
                    assertThat(mapper.readTree(raw).path("event_id").asText())
                        .startsWith("buddystudy-internal-duplex-turn-response-")
                    assertThat(raw).doesNotContain("continuous_intervention", "response.cancel", "output_audio_buffer.clear")
                }
                .verifyComplete()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `manual deferred checkpoint waits for older commit but never grants away the current floor`(): Unit {
        val now = AtomicLong()
        val controller = controller(nanoTime = now::get, transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        try {
            StepVerifier.create(providerEvents(controller, includeInputCommits = true).take(4))
                .then {
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                    controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))
                }
                .assertNext(::assertServerOwnedInputCommit)
                .then {
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 2))
                    controller.fireContinuousSpeechDeadline()
                }
                .expectNoEvent(Duration.ofMillis(10))
                .then {
                    now.set(Duration.ofSeconds(1).toNanos())
                    controller.observeProviderEvent(committedEvent("older-learner-utterance"))
                }
                .assertNext(::assertServerOwnedInputCheckpoint)
                .then { controller.observeProviderEvent(committedEvent("current-checkpoint")) }
                .expectNoEvent(Duration.ofMillis(10))
                .then { controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 2)) }
                .assertNext(::assertServerOwnedInputCommit)
                .then { controller.observeProviderEvent(committedEvent("current-tail")) }
                .assertNext { raw ->
                    assertThat(mapper.readTree(raw).path("event_id").asText())
                        .startsWith("buddystudy-internal-duplex-turn-response-")
                    assertThat(raw).doesNotContain("continuous_intervention", "response.cancel", "output_audio_buffer.clear")
                }
                .verifyComplete()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `manual input ignores provider VAD and unsolicited or replayed acknowledgements`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        try {
            StepVerifier.create(providerEvents(controller, includeInputCommits = true).take(2))
                .then {
                    for (type in listOf("input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped")) {
                        assertThat(controller.observeProviderEvent(event(type)))
                            .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
                    }
                    assertThat(controller.observeProviderEvent(committedEvent("unsolicited")))
                        .isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                    controller.observeProviderEvent(committedEvent("unsolicited-during-speech"))
                }
                .expectNoEvent(Duration.ofMillis(10))
                .then { controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1)) }
                .assertNext(::assertServerOwnedInputCommit)
                .then {
                    controller.observeProviderEvent(committedEvent("unsolicited"))
                    controller.observeProviderEvent(committedEvent("unsolicited-during-speech"))
                    controller.observeProviderEvent(event("input_audio_buffer.committed"))
                }
                .expectNoEvent(Duration.ofMillis(10))
                .then { controller.observeProviderEvent(committedEvent("actual-manual-commit")) }
                .assertNext { raw -> assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create") }
                .verifyComplete()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `manual notification sequences must be positive integral Long values`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        try {
            StepVerifier.create(providerEvents(controller, includeInputCommits = true).take(1))
                .then {
                    for (value in listOf("null", "0", "-1", "1.5", "\"1\"", "9223372036854775808", "true", "{}")) {
                        for (type in listOf(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT)) {
                            assertThat(controller.observeClientEvent("""{"type":"$type","sequence":$value}""")).isTrue()
                        }
                    }
                    controller.observeClientEvent(event(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT))
                    controller.observeClientEvent(event(VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT))
                }
                .expectNoEvent(Duration.ofMillis(10))
                .then {
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = Long.MAX_VALUE))
                    controller.observeClientEvent(clientSpeechEvent(started = false, sequence = Long.MAX_VALUE))
                }
                .assertNext(::assertServerOwnedInputCommit)
                .verifyComplete()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `manual notification types do not change legacy provider VAD state or emit raw events`() {
        val controller = controller()
        try {
            StepVerifier.create(providerEvents(controller, includeInputCommits = true).take(1))
                .then {
                    assertThat(controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))).isTrue()
                    assertThat(controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))).isTrue()
                }
                .expectNoEvent(Duration.ofMillis(10))
                .then { learnerTurn(controller) }
                .assertNext { raw -> assertThat(mapper.readTree(raw).path("type").asText()).isEqualTo("response.create") }
                .verifyComplete()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `manual pending commit limit fails explicitly rather than dropping captured utterances`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        val emitted = mutableListOf<String>()
        val subscription = providerEvents(controller, includeInputCommits = true).subscribe(emitted::add)
        try {
            for (sequence in 1L..32L) {
                controller.observeClientEvent(clientSpeechEvent(started = true, sequence = sequence))
                controller.observeClientEvent(clientSpeechEvent(started = false, sequence = sequence))
            }
            assertThat(emitted).hasSize(32)
            emitted.forEach(::assertServerOwnedInputCommit)
            assertThatThrownBy {
                controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 33))
            }.isInstanceOf(VoiceTutorPendingInputCommitOverflowException::class.java)
            assertThat(emitted).hasSize(32)
        } finally {
            subscription.dispose()
            controller.close()
        }
    }

    @Test
    fun `manual commit acknowledgement timeout is terminal without sending a response cancellation`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        try {
            StepVerifier.create(providerEvents(controller, includeInputCommits = true))
                .then {
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                    controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))
                }
                .assertNext(::assertServerOwnedInputCommit)
                .then { controller.fireInputCommitTimeout(1) }
                .expectError(VoiceTutorProviderInputCommitTimeoutException::class.java)
                .verify()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `additional manual commits cannot extend the oldest acknowledgement deadline`() {
        val clock = AtomicLong(0)
        lateinit var controller: VoiceTutorDuplexTurnController
        try {
            StepVerifier.withVirtualTime {
                controller = this.controller(nanoTime = clock::get, transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
                providerEvents(controller, includeInputCommits = true)
            }
                .then {
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
                    controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))
                }
                .assertNext(::assertServerOwnedInputCommit)
                .expectNoEvent(Duration.ofSeconds(59))
                .then {
                    clock.set(Duration.ofSeconds(59).toNanos())
                    controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 2))
                    controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 2))
                }
                .assertNext(::assertServerOwnedInputCommit)
                .expectNoEvent(Duration.ofMillis(999))
                .thenAwait(Duration.ofMillis(1))
                .expectError(VoiceTutorProviderInputCommitTimeoutException::class.java)
                .verify(Duration.ofSeconds(2))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `terminal state ignores late manual stops starts acknowledgements and timeout callbacks`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        val emitted = mutableListOf<String>()
        val subscription = providerEvents(controller, includeInputCommits = true).subscribe(emitted::add)
        try {
            controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 1))
            controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 1))
            controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 2))
            assertThat(emitted).hasSize(1)
            assertServerOwnedInputCommit(emitted.single())
            controller.close()

            controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 2))
            controller.observeClientEvent(clientSpeechEvent(started = true, sequence = 3))
            controller.observeClientEvent(clientSpeechEvent(started = false, sequence = 3))
            controller.observeProviderEvent(committedEvent("late-commit"))
            controller.fireInputCommitTimeout(1)
            controller.fireContinuousSpeechDeadline()
            controller.startOpeningResponse()
            assertThat(emitted).hasSize(1)
        } finally {
            subscription.dispose()
            controller.close()
        }
    }

    @Test
    fun `webrtc cleared or non-completed response is terminal`() {
        listOf("cancelled", "incomplete", "failed").forEach { status ->
            val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
            val control = providerEvents(controller).next().doOnSubscribe { queueOrdinaryResponse(controller) }.block()!!
            controller.observeProviderEvent(responseEvent("response.created", "response-$status", control))

            assertThatThrownBy {
                controller.observeProviderEvent(
                    responseEvent("response.done", "response-$status", control, status = status),
                )
            }.isInstanceOf(VoiceTutorProviderIncompleteResponseException::class.java)
            controller.close()
        }

        val clearedController = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        val clearedControl = providerEvents(clearedController).next()
            .doOnSubscribe { queueOrdinaryResponse(clearedController) }
            .block()!!
        clearedController.observeProviderEvent(
            responseEvent("response.created", "response-cleared", clearedControl),
        )
        assertThatThrownBy {
            clearedController.observeProviderEvent(
                outputBufferEvent("output_audio_buffer.cleared", "response-cleared"),
            )
        }.isInstanceOf(VoiceTutorProviderOutputBufferClearedException::class.java)
        clearedController.close()
    }

    @Test
    fun `webrtc audio deltas stay off the client control path`() {
        val controller = controller(transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND)
        val control = providerEvents(controller).next().doOnSubscribe { queueOrdinaryResponse(controller) }.block()!!
        controller.observeProviderEvent(responseEvent("response.created", "response-audio", control))

        assertThat(controller.observeProviderEvent(audioDeltaEvent("response-audio")))
            .isEqualTo(VoiceTutorProviderRelayDisposition.PERSIST_ONLY)
        controller.close()
    }

    @Test
    fun `terminal blocks responses and keeps final transcripts persistence only`() {
        val controller = controller()
        val activeControl = AtomicReference<String>()
        val completed = AtomicBoolean(false)
        val emittedControls = AtomicLong(0)
        val subscription = providerEvents(controller).subscribe(
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

    private fun controller(
        nanoTime: () -> Long = System::nanoTime,
        transport: VoiceTutorRealtimeTransport = VoiceTutorRealtimeTransport.LEGACY_PCM_RELAY,
        readyForLearnerTurns: Boolean = true,
    ): VoiceTutorDuplexTurnController {
        val controller = VoiceTutorDuplexTurnController(
            mapper = mapper,
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            nanoTime = nanoTime,
            transport = transport,
        )
        controllerTransports[controller] = transport
        if (!readyForLearnerTurns) return controller

        // Mid-conversation tests enter through the real opening lifecycle, then
        // expose only subsequent controls; no production readiness gate is bypassed.
        val buffered = Sinks.many().unicast().onBackpressureBuffer<String>()
        val opening = mutableListOf<String>()
        var preparing = true
        val subscription = controller.providerEvents().subscribe(
            { raw -> if (preparing) opening += raw else buffered.tryEmitNext(raw) },
            { error -> buffered.tryEmitError(error) },
            { buffered.tryEmitComplete() },
        )
        controller.startOpeningResponse()
        assertThat(opening).hasSize(1)
        controller.observeProviderEvent(responseEvent("response.created", "fixture-opening", opening.single()))
        controller.observeProviderEvent(responseEvent("response.done", "fixture-opening", opening.single()))
        if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped", "fixture-opening"))
        }
        preparing = false
        preparedProviderEvents[controller] = buffered.asFlux().doFinally { subscription.dispose() }
        return controller
    }

    private fun providerEvents(
        controller: VoiceTutorDuplexTurnController,
        includeInputCommits: Boolean = false,
    ): Flux<String> {
        val events = preparedProviderEvents[controller] ?: controller.providerEvents()
        // Existing turn/playout assertions observe tutor controls. Dedicated
        // manual-VAD tests below include and assert every real input commit too.
        return if (includeInputCommits) events else events.filter {
            mapper.readTree(it).path("type").asText() != "input_audio_buffer.commit"
        }
    }

    private fun queueOrdinaryResponse(controller: VoiceTutorDuplexTurnController) {
        learnerTurn(controller)
    }

    private fun learnerTurn(controller: VoiceTutorDuplexTurnController) {
        if (controllerTransports[controller] == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
            val sequence = (clientSpeechSequences[controller] ?: 0L) + 1
            clientSpeechSequences[controller] = sequence
            controller.observeClientEvent(clientSpeechEvent(started = true, sequence = sequence))
            controller.observeClientEvent(clientSpeechEvent(started = false, sequence = sequence))
            controller.observeProviderEvent(committedEvent("fixture-learner-$sequence"))
            return
        }
        controller.observeProviderEvent(event("input_audio_buffer.speech_started"))
        controller.observeProviderEvent(event("input_audio_buffer.speech_stopped"))
        controller.observeProviderEvent(event("input_audio_buffer.committed"))
    }

    private fun event(type: String): String = """{"type":"$type"}"""

    private fun clientSpeechEvent(started: Boolean, sequence: Long): String = mapper.writeValueAsString(
        mapOf(
            "type" to if (started) VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT else VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT,
            "sequence" to sequence,
        ),
    )

    private fun committedEvent(itemId: String): String = mapper.writeValueAsString(
        mapOf("type" to "input_audio_buffer.committed", "item_id" to itemId),
    )

    private fun assertServerOwnedInputCommit(raw: String) {
        val commit = mapper.readTree(raw)
        assertThat(commit.path("type").asText()).isEqualTo("input_audio_buffer.commit")
        assertThat(commit.path("event_id").asText()).startsWith("buddystudy-internal-duplex-input-commit-")
        assertThat(commit.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("event_id", "type")
    }

    private fun assertServerOwnedInputCheckpoint(raw: String) {
        val commit = mapper.readTree(raw)
        assertThat(commit.path("type").asText()).isEqualTo("input_audio_buffer.commit")
        assertThat(commit.path("event_id").asText()).startsWith("buddystudy-internal-duplex-input-checkpoint-")
        assertThat(commit.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("event_id", "type")
    }

    private fun permutations(values: List<String>): List<List<String>> =
        if (values.isEmpty()) listOf(emptyList()) else values.flatMap { first ->
            permutations(values - first).map { listOf(first) + it }
        }

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

    private fun playoutDrainedEvent(id: String): String =
        """{"type":"buddystudy.voice.playout.drained","responseId":"$id"}"""

    private fun outputBufferEvent(type: String, id: String): String =
        """{"type":"$type","response_id":"$id"}"""

    private fun applyWebRtcGateSignal(
        controller: VoiceTutorDuplexTurnController,
        control: String,
        signal: String,
    ) {
        when (signal) {
            "done" -> controller.observeProviderEvent(
                responseEvent("response.done", "response-webrtc", control),
            )
            "provider-stopped" -> controller.observeProviderEvent(
                outputBufferEvent("output_audio_buffer.stopped", "response-webrtc"),
            )
            else -> error("Unknown gate signal: $signal")
        }
    }
}
