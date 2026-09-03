package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorQuotaExhaustionPolicy
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorSpokenTerminationNotice
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import reactor.core.Disposable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenAIVoiceTutorWebRtcAdapterTest {
    private val mapper = JsonMapperProvider.mapper
    private val adapter = OpenAIVoiceTutorWebRtcAdapter(
        BuddyStudyProperties(),
        object : VoiceTutorInputAssessmentUseCase {
            override suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult =
                error("A configuration/negotiation test must never request input assessment.")
        },
    )

    @Test
    fun `component implements the server owned webrtc port`() {
        val port: VoiceTutorWebRtcPort = adapter

        assertThat(port).isSameAs(adapter)
    }

    @Test
    fun `unified call configuration leaves media encoding to webrtc and disables provider turn detection`() {
        val session = mapper.readTree(
            adapter.webRtcSessionConfiguration(
                VoiceTutorRealtimeRequest(
                    userId = 42,
                    model = "gpt-realtime-test",
                    voice = "marin",
                    instructions = "Use one complete sentence.",
                    language = "ko",
                ),
            ),
        )

        assertThat(session.path("type").asText()).isEqualTo("realtime")
        assertThat(session.path("model").asText()).isEqualTo("gpt-realtime-test")
        assertThat(session.path("instructions").asText()).isEqualTo("Use one complete sentence.")
        assertThat(session.path("audio").path("output").path("voice").asText()).isEqualTo("marin")
        assertThat(session.toString()).doesNotContain("audio/pcm", "24000")

        val turnDetection = session.path("audio").path("input").path("turn_detection")
        assertThat(turnDetection.isNull).isTrue()
        assertThat(session.path("audio").path("input").has("turn_detection")).isTrue()
    }

    @Test
    fun `sdp validator accepts exactly one audio media section`() {
        val offer = validSdp(includeDataChannel = false)

        assertThat(validateWebRtcSdp(offer)).isEqualTo(offer)
    }

    @Test
    fun `sdp validator rejects malformed oversized or unexpected media offers`() {
        val invalidOffers = listOf(
            "",
            validSdp().replaceFirst("v=0", "v=1"),
            validSdp().replace("a=fingerprint:$FINGERPRINT\r\n", ""),
            validSdp().replace("a=ice-ufrag:buddy\r\n", ""),
            validSdp().replace("m=audio 9 UDP/TLS/RTP/SAVPF 111", "m=video 9 UDP/TLS/RTP/SAVPF 111"),
            validSdp(includeDataChannel = true),
            validSdp() + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n",
            validSdp() + "m=image 9 TCP image\r\n",
            validSdp() + "\u0000",
            "v=0\r\n" + "x".repeat(65_536),
        )

        invalidOffers.forEach { offer ->
            assertThatThrownBy { validateWebRtcSdp(offer) }
                .isInstanceOf(VoiceTutorWebRtcSdpException::class.java)
        }
    }

    @Test
    fun `location parser accepts only exact OpenAI realtime call locations`() {
        assertThat(callIdFromLocation("/v1/realtime/calls/rtc_u1_abc-123"))
            .isEqualTo("rtc_u1_abc-123")
        assertThat(callIdFromLocation("https://api.openai.com/v1/realtime/calls/rtc_123"))
            .isEqualTo("rtc_123")

        listOf(
            null,
            "",
            "/v1/realtime/calls/not-a-call",
            "/v1/realtime/calls/rtc_123/extra",
            "/v1/realtime/calls/rtc_123?redirect=https://attacker.invalid",
            "https://attacker.invalid/v1/realtime/calls/rtc_123",
            "http://api.openai.com/v1/realtime/calls/rtc_123",
            "https://api.openai.com:444/v1/realtime/calls/rtc_123",
            "//api.openai.com/v1/realtime/calls/rtc_123",
            "/v1/realtime/calls/%72tc_123",
        ).forEach { location ->
            assertThatThrownBy { callIdFromLocation(location) }
                .isInstanceOf(VoiceTutorWebRtcCallIdException::class.java)
        }
    }

    @Test
    fun `sideband call id validation rejects path and query injection`() {
        listOf("rtc_123", "rtc_u1_abc-123", "rtc_${"x".repeat(187)}").forEach { callId ->
            assertThat(validateWebRtcCallId(callId)).isEqualTo(callId)
        }

        listOf("", "call_123", "rtc_123/../other", "rtc_123?x=1", "rtc_${"x".repeat(188)}").forEach { callId ->
            assertThatThrownBy { validateWebRtcCallId(callId) }
                .isInstanceOf(VoiceTutorWebRtcCallIdException::class.java)
        }
    }

    @Test
    fun `hangup treats provider already-ended statuses as success`() {
        assertThat(HttpStatus.NOT_FOUND.isAlreadyEndedCall()).isTrue()
        assertThat(HttpStatus.CONFLICT.isAlreadyEndedCall()).isTrue()
        assertThat(HttpStatus.GONE.isAlreadyEndedCall()).isTrue()
        assertThat(HttpStatus.INTERNAL_SERVER_ERROR.isAlreadyEndedCall()).isFalse()
    }

    @Test
    fun `quota relay release waits for exact notice playout and paid boundary`() {
        val base = Instant.parse("2031-08-30T00:00:00Z")
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            sessionLanguage = "ko",
        )
        val controls = CopyOnWriteArrayList<String>()
        val output = controller.providerEvents().subscribe(controls::add)
        val termination = VoiceTutorRelayTermination(
            cancelActiveResponse = false,
            spokenNotice = VoiceTutorSpokenTerminationNotice.MONTHLY_QUOTA_EXHAUSTED,
            notBefore = base.plusSeconds(5),
        )
        try {
            StepVerifier.withVirtualTime {
                quotaExhaustionRelayRelease(controller, termination) { base }
            }
                .then {
                    controller.requestQuotaExhaustionNotice()
                    val create = controls.last()
                    controller.observeProviderEvent(providerResponseEvent("response.created", create))
                    controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.started"))
                    controller.observeProviderEvent(
                        tutorTranscriptEvent("이번 안내로 이번 달 음성 시간이 모두 소진되어 통화를 종료할게요."),
                    )
                    controller.observeProviderEvent(providerResponseEvent("response.done", create))
                    controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped"))
                    controller.observeClientEvent(
                        """{"type":"${VoiceTutorRealtimeContract.PLAYOUT_DRAINED_EVENT}","responseId":"$RESPONSE_ID"}""",
                    )
                }
                .expectNoEvent(Duration.ofSeconds(4))
                .thenAwait(Duration.ofSeconds(1))
                .verifyComplete()
        } finally {
            output.dispose()
            controller.close()
        }
    }

    @Test
    fun `quota relay release times out after boundary grace when device never drains playout`() {
        val base = Instant.parse("2031-08-30T00:00:00Z")
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
        )
        val controls = CopyOnWriteArrayList<String>()
        val output = controller.providerEvents().subscribe(controls::add)
        val termination = VoiceTutorRelayTermination(
            cancelActiveResponse = false,
            spokenNotice = VoiceTutorSpokenTerminationNotice.MONTHLY_QUOTA_EXHAUSTED,
            notBefore = base.plusSeconds(5),
        )
        try {
            StepVerifier.withVirtualTime {
                quotaExhaustionRelayRelease(controller, termination) { base }
            }
                .then {
                    controller.requestQuotaExhaustionNotice()
                    val create = controls.last()
                    controller.observeProviderEvent(providerResponseEvent("response.created", create))
                    controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.started"))
                    controller.observeProviderEvent(
                        tutorTranscriptEvent("이번 안내로 이번 달 음성 시간이 모두 소진되어 통화를 종료할게요."),
                    )
                    controller.observeProviderEvent(providerResponseEvent("response.done", create))
                    controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped"))
                    // Deliberately omit the exact client playout-drained ACK.
                }
                .expectNoEvent(Duration.ofSeconds(24))
                .thenAwait(Duration.ofSeconds(1))
                .verifyComplete()
        } finally {
            output.dispose()
            controller.close()
        }
    }

    @Test
    fun `quota relay release uses only grace remaining after the hard end`() {
        val hardEndsAt = Instant.parse("2031-08-30T00:00:00Z")
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
        )
        val termination = VoiceTutorRelayTermination(
            cancelActiveResponse = false,
            spokenNotice = VoiceTutorSpokenTerminationNotice.MONTHLY_QUOTA_EXHAUSTED,
            notBefore = hardEndsAt,
        )
        val oneSecondBeforeDeadline = hardEndsAt.plusSeconds(
            VoiceTutorQuotaExhaustionPolicy.NOTICE_GRACE_SECONDS - 1,
        )

        StepVerifier.withVirtualTime {
            quotaExhaustionRelayRelease(controller, termination) { oneSecondBeforeDeadline }
        }
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(999))
            .thenAwait(Duration.ofMillis(1))
            .verifyComplete()

        assertThat(controller.acceptsInputEvents()).isFalse()
    }

    @Test
    fun `quota relay release expires immediately at the absolute grace deadline`() {
        val hardEndsAt = Instant.parse("2031-08-30T00:00:00Z")
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
        )
        val termination = VoiceTutorRelayTermination(
            cancelActiveResponse = false,
            spokenNotice = VoiceTutorSpokenTerminationNotice.MONTHLY_QUOTA_EXHAUSTED,
            notBefore = hardEndsAt,
        )
        val absoluteDeadline = hardEndsAt.plusSeconds(VoiceTutorQuotaExhaustionPolicy.NOTICE_GRACE_SECONDS)

        StepVerifier.withVirtualTime {
            quotaExhaustionRelayRelease(controller, termination) { absoluteDeadline }
        }
            .expectSubscription()
            .verifyComplete()

        assertThat(controller.acceptsInputEvents()).isFalse()
    }

    @Test
    fun `ancillary completion and error cannot beat an active quota relay grace`() {
        listOf(Mono.empty<Void>(), Mono.error<Void>(IllegalStateException("assessment failed"))).forEach { work ->
            val release = Sinks.one<Boolean>()
            StepVerifier.create(
                holdVoiceTutorSignalForGrace(work, release.asMono()) { true },
            )
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(20))
                .then { release.tryEmitValue(true) }
                .verifyComplete()
        }
    }

    @Test
    fun `provider call callback precedes invalid SDP validation`() = runBlocking<Unit> {
        val callIds = mutableListOf<String>()
        val response = ClientResponse.create(HttpStatus.OK)
            .header(HttpHeaders.LOCATION, "/v1/realtime/calls/rtc_invalid-sdp")
            .body("not-an-sdp")
            .build()

        val failure = runCatching {
            adapter.readNegotiationResponse(response) { callIds += it }.awaitSingle()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(VoiceTutorWebRtcSdpException::class.java)
        assertThat(callIds).containsExactly("rtc_invalid-sdp")
    }

    @Test
    fun `provider call callback precedes SDP body read failure`() = runBlocking<Unit> {
        val callIds = mutableListOf<String>()
        val bodyFailure = IllegalStateException("provider body failed")
        val response = ClientResponse.create(HttpStatus.OK)
            .header(HttpHeaders.LOCATION, "/v1/realtime/calls/rtc_body-failure")
            .body(Flux.error<DataBuffer>(bodyFailure))
            .build()

        val failure = runCatching {
            adapter.readNegotiationResponse(response) { callIds += it }.awaitSingle()
        }.exceptionOrNull()

        assertThat(failure)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("provider body failed")
        assertThat(failure?.cause).isSameAs(bodyFailure)
        assertThat(callIds).containsExactly("rtc_body-failure")
    }

    @Test
    fun `provider SDP body cancellation remains unwrapped after call callback`() = runBlocking<Unit> {
        val callIds = mutableListOf<String>()
        val cancellation = CancellationException("provider body cancelled")
        val response = ClientResponse.create(HttpStatus.OK)
            .header(HttpHeaders.LOCATION, "/v1/realtime/calls/rtc_body-cancelled")
            .body(Flux.error<DataBuffer>(cancellation))
            .build()

        val failure = runCatching {
            adapter.readNegotiationResponse(response) { callIds += it }.awaitSingle()
        }.exceptionOrNull()

        assertThat(failure)
            .isInstanceOf(CancellationException::class.java)
            .hasMessage("provider body cancelled")
        assertThat(failure?.cause).isSameAs(cancellation)
        assertThat(callIds).containsExactly("rtc_body-cancelled")
    }

    @Test
    fun `sideband ready is emitted only after provider receive and send are subscribed`() {
        val subscriptions = mutableListOf<String>()
        var readyEmitted = false
        val receive = Mono.never<Void>().doOnSubscribe { subscriptions += "receive" }
        val send = Mono.never<Void>().doOnSubscribe { subscriptions += "send" }
        val ready = Mono.fromRunnable<Void> {
            assertThat(subscriptions).containsExactly("receive", "send")
            readyEmitted = true
        }.then()

        val diagnostics = VoiceTutorSidebandDiagnostics("rtc_ready")
        val relay = webRtcSidebandLifecycle(receive, send, ready, diagnostics).subscribe()

        assertThat(readyEmitted).isTrue()
        assertThat(relay.isDisposed).isFalse()
        relay.dispose()
    }

    @TestFactory
    fun `production relay persists the completed tutor boundary before releasing the next response`() =
        listOf("done-then-stopped", "stopped-then-done").map { ordering ->
            dynamicTest(ordering) {
                val fixture = postRelayFixture("persisted tutor sentence")
                val calls = CopyOnWriteArrayList<RelayCall>()
                val errors = CopyOnWriteArrayList<Throwable>()
                val transcriptStarted = CountDownLatch(1)
                val releaseTranscript = CountDownLatch(1)
                val relayFinished = CountDownLatch(1)
                try {
                    val finalRaw = fixture.finalBoundaryEvent(ordering)
                    val finalType = mapper.readTree(finalRaw).path("type").asText()
                    val relay = relayVoiceTutorProviderEvent(fixture.controller, finalRaw) { raw, persist, forward ->
                        val type = mapper.readTree(raw).path("type").asText()
                        calls += RelayCall(type, persist, forward)
                        if (type == "response.output_audio_transcript.done") {
                            transcriptStarted.countDown()
                            check(releaseTranscript.await(5, TimeUnit.SECONDS))
                        }
                        true
                    }.subscribeOn(Schedulers.boundedElastic())
                        .doFinally { relayFinished.countDown() }
                        .subscribe({}, errors::add)

                    assertThat(transcriptStarted.await(2, TimeUnit.SECONDS)).isTrue()
                    assertThat(calls.map { it.type }).containsExactly(
                        finalType,
                        "response.output_audio_transcript.done",
                    )
                    assertThat(calls.last()).isEqualTo(
                        RelayCall("response.output_audio_transcript.done", persist = true, forward = false),
                    )
                    assertThat(fixture.responseCreates()).hasSize(1)
                    assertThat(fixture.lifecycle).isEmpty()

                    releaseTranscript.countDown()
                    assertThat(relayFinished.await(2, TimeUnit.SECONDS)).isTrue()
                    assertThat(errors).isEmpty()
                    assertThat(fixture.responseCreates()).hasSize(2)
                    assertThat(fixture.lifecycle).isEmpty()
                    relay.dispose()
                } finally {
                    releaseTranscript.countDown()
                    fixture.close()
                }
            }
        }

    @Test
    fun `transcript free spoken success remains fenced until its raw proof relay completes`() {
        val fixture = postRelayFixture(transcript = null)
        val proofStarted = CountDownLatch(1)
        val releaseProof = CountDownLatch(1)
        val relayFinished = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<RelayCall>()
        val errors = CopyOnWriteArrayList<Throwable>()
        try {
            val finalRaw = fixture.finalBoundaryEvent("done-then-stopped")
            val relay = relayVoiceTutorProviderEvent(fixture.controller, finalRaw) { raw, persist, forward ->
                calls += RelayCall(mapper.readTree(raw).path("type").asText(), persist, forward)
                proofStarted.countDown()
                check(releaseProof.await(5, TimeUnit.SECONDS))
                true
            }.subscribeOn(Schedulers.boundedElastic())
                .doFinally { relayFinished.countDown() }
                .subscribe({}, errors::add)

            assertThat(proofStarted.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(fixture.responseCreates()).hasSize(1)
            releaseProof.countDown()
            assertThat(relayFinished.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(errors).isEmpty()
            assertThat(calls.map { it.type }).containsExactly("output_audio_buffer.stopped")
            assertThat(fixture.responseCreates()).hasSize(2)
            relay.dispose()
        } finally {
            releaseProof.countDown()
            fixture.close()
        }
    }

    @Test
    fun `same tutor item content parts persist as one ordered row before boundary ack`() {
        val fixture = postRelayFixture(transcript = null)
        try {
            fixture.controller.observeProviderEvent(
                tutorTranscriptEvent("part one", contentIndex = 1),
            )
            fixture.controller.observeProviderEvent(
                tutorTranscriptEvent("part zero", contentIndex = 0),
            )
            val finalRaw = fixture.finalBoundaryEvent("done-then-stopped")
            val persisted = CopyOnWriteArrayList<String>()

            StepVerifier.create(
                relayVoiceTutorProviderEvent(fixture.controller, finalRaw) { raw, persist, _ ->
                    if (persist && mapper.readTree(raw).path("type").asText() ==
                        "response.output_audio_transcript.done"
                    ) {
                        persisted += raw
                    }
                    true
                },
            ).verifyComplete()

            assertThat(persisted).hasSize(1)
            val transcript = mapper.readTree(persisted.single())
            assertThat(transcript.path("item_id").asText()).isEqualTo(TUTOR_ITEM_ID)
            assertThat(transcript.path("content_index").asInt()).isZero()
            assertThat(transcript.path("transcript").asText()).isEqualTo("part zero\npart one")
            assertThat(fixture.responseCreates()).hasSize(2)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `reversed tutor content parts release the same ordered assessment context as persistence`() {
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            inputCoordinator = VoiceTutorInputTurnCoordinator(),
        )
        val controls = CopyOnWriteArrayList<String>()
        val actions = CopyOnWriteArrayList<VoiceTutorInputTurnCoordinator.Action>()
        val subscriptions = listOf(
            controller.providerEvents().subscribe(controls::add),
            controller.inputActions().subscribe(actions::add),
        )
        try {
            controller.startOpeningResponse()
            val opening = controls.single()
            controller.observeProviderEvent(providerResponseEvent("response.created", opening))
            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.started"))
            controller.observeClientEvent(
                """{"type":"buddystudy.voice.input.speech.started","sequence":1}""",
            )
            controller.observeClientEvent(
                """{"type":"buddystudy.voice.input.speech.stopped","sequence":1}""",
            )
            controller.observeProviderEvent(
                """{"type":"input_audio_buffer.committed","item_id":"learner-parts"}""",
            )
            controller.observeProviderEvent(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"learner-parts","transcript":"설명해 주세요"}""",
            )
            controller.observeProviderEvent(tutorTranscriptEvent("part one", contentIndex = 1))
            controller.observeProviderEvent(tutorTranscriptEvent("part zero", contentIndex = 0))
            controller.observeProviderEvent(providerResponseEvent("response.done", opening))

            val observed = controller.observeProviderEventWithPostRelay(
                outputBufferEvent("output_audio_buffer.stopped"),
            )
            val boundary = observed.postRelayBoundary!!
            assertThat(actions).isEmpty()
            assertThat(mapper.readTree(boundary.tutorTranscriptEvents.single()).path("transcript").asText())
                .isEqualTo("part zero\npart one")

            assertThat(controller.acknowledgePostRelayBoundary(boundary.token)).isTrue()
            assertThat(actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Assess>()
                .single().teacherContext).isEqualTo("part zero\npart one")
        } finally {
            controller.close()
            subscriptions.forEach(Disposable::dispose)
        }
    }

    @Test
    fun `failed tutor persistence never acknowledges or releases the next response`() {
        listOf<(String) -> Boolean>(
            { false },
            { throw IllegalStateException("database unavailable") },
        ).forEachIndexed { index, persistence ->
            val fixture = postRelayFixture("must remain staged")
            try {
                val finalRaw = fixture.finalBoundaryEvent("done-then-stopped")
                val failure = runCatching {
                    relayVoiceTutorProviderEvent(fixture.controller, finalRaw) { raw, _, _ ->
                        if (mapper.readTree(raw).path("type").asText() ==
                            "response.output_audio_transcript.done"
                        ) {
                            persistence(raw)
                        } else {
                            true
                        }
                    }.block(Duration.ofSeconds(2))
                }.exceptionOrNull()

                if (index == 0) {
                    assertThat(failure).isInstanceOf(VoiceTutorTutorTranscriptPersistenceException::class.java)
                } else {
                    assertThat(failure).isInstanceOf(IllegalStateException::class.java)
                        .hasMessage("database unavailable")
                }
                assertThat(fixture.responseCreates()).hasSize(1)
                assertThat(fixture.lifecycle).isEmpty()
                assertThat(fixture.controller.acceptsInputEvents()).isTrue()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `cancelled tutor persistence never acknowledges the completed boundary`() {
        val fixture = postRelayFixture("cancelled persistence")
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val persistenceExited = CountDownLatch(1)
        try {
            val finalRaw = fixture.finalBoundaryEvent("done-then-stopped")
            val relay = relayVoiceTutorProviderEvent(fixture.controller, finalRaw) { raw, _, _ ->
                if (mapper.readTree(raw).path("type").asText() == "response.output_audio_transcript.done") {
                    persistenceStarted.countDown()
                    try {
                        releasePersistence.await(5, TimeUnit.SECONDS)
                    } finally {
                        persistenceExited.countDown()
                    }
                }
                true
            }.subscribeOn(Schedulers.boundedElastic()).subscribe()

            assertThat(persistenceStarted.await(2, TimeUnit.SECONDS)).isTrue()
            relay.dispose()
            releasePersistence.countDown()
            assertThat(persistenceExited.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(fixture.responseCreates()).hasSize(1)
            assertThat(fixture.lifecycle).isEmpty()
        } finally {
            releasePersistence.countDown()
            fixture.close()
        }
    }

    @Test
    fun `atomic completed batch survives controller close after provider observation`() {
        val fixture = postRelayFixture("owned before close")
        val calls = CopyOnWriteArrayList<String>()
        try {
            val finalRaw = fixture.finalBoundaryEvent("done-then-stopped")
            val relay = relayVoiceTutorProviderEvent(fixture.controller, finalRaw) { raw, _, _ ->
                calls += mapper.readTree(raw).path("type").asText()
                true
            }
            fixture.controller.close()

            relay.block(Duration.ofSeconds(2))

            assertThat(calls).containsExactly(
                "output_audio_buffer.stopped",
                "response.output_audio_transcript.done",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `spoken lesson end assessment waits for persisted tutor boundary acknowledgement`() {
        val assessments = CopyOnWriteArrayList<VoiceTutorInputAssessmentRequest>()
        val persisted = CopyOnWriteArrayList<String>()
        val lifecycle = CopyOnWriteArrayList<String>()
        val controls = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val tutorPersistenceStarted = CountDownLatch(1)
        val releaseTutorPersistence = CountDownLatch(1)
        val lessonEnded = CountDownLatch(1)
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            inputCoordinator = VoiceTutorInputTurnCoordinator(),
        )
        val assessment = object : VoiceTutorInputAssessmentUseCase {
            override suspend fun assess(request: VoiceTutorInputAssessmentRequest): VoiceTutorInputAssessmentResult {
                assessments += request
                return VoiceTutorInputAssessmentResult(
                    request.utterances.map { utterance ->
                        VoiceTutorInputItemAssessment(
                            itemId = utterance.itemId,
                            decision = VoiceTutorInputDecision.MEANINGFUL,
                            intent = VoiceTutorInputIntent.END_CURRENT_VOICE_LESSON,
                        )
                    },
                )
            }
        }
        val onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean = { raw, persist, _ ->
            val type = mapper.readTree(raw).path("type").asText()
            if (type == "response.output_audio_transcript.done") {
                tutorPersistenceStarted.countDown()
                check(releaseTutorPersistence.await(5, TimeUnit.SECONDS))
            }
            if (persist) persisted += raw
            true
        }
        val subscriptions = listOf(
            controller.providerEvents().subscribe(controls::add, errors::add),
            controller.serverLifecycleEvents().subscribe({ raw ->
                lifecycle += raw
                lessonEnded.countDown()
            }, errors::add),
            voiceTutorInputAssessmentRelay(
                controller = controller,
                userId = 42,
                language = "ko",
                assessment = assessment,
                onProviderEvent = onProviderEvent,
            ).subscribe({}, errors::add),
        )
        try {
            controller.startOpeningResponse()
            val opening = controls.single { mapper.readTree(it).path("type").asText() == "response.create" }
            controller.observeProviderEvent(providerResponseEvent("response.created", opening))
            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.started"))
            controller.observeProviderEvent(tutorTranscriptEvent("끝내기 전 마지막 선생님 문장"))
            controller.observeClientEvent(
                """{"type":"buddystudy.voice.input.speech.started","sequence":1}""",
            )
            controller.observeClientEvent(
                """{"type":"buddystudy.voice.input.speech.stopped","sequence":1}""",
            )
            controller.observeProviderEvent(
                """{"type":"input_audio_buffer.committed","item_id":"learner-end"}""",
            )
            controller.observeProviderEvent(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"learner-end","transcript":"학습 종료할게"}""",
            )
            controller.observeProviderEvent(providerResponseEvent("response.done", opening))
            assertThat(assessments).isEmpty()
            assertThat(lifecycle).isEmpty()

            val relayFinished = CountDownLatch(1)
            val relay = relayVoiceTutorProviderEvent(
                controller,
                outputBufferEvent("output_audio_buffer.stopped"),
                onProviderEvent = onProviderEvent,
            ).subscribeOn(Schedulers.boundedElastic())
                .doFinally { relayFinished.countDown() }
                .subscribe({}, errors::add)
            assertThat(tutorPersistenceStarted.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(assessments).isEmpty()
            assertThat(lifecycle).isEmpty()

            releaseTutorPersistence.countDown()
            assertThat(relayFinished.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(lessonEnded.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(errors).isEmpty()
            assertThat(assessments).hasSize(1)
            assertThat(assessments.single().teacherContext).isEqualTo("끝내기 전 마지막 선생님 문장")
            assertThat(persisted.map { mapper.readTree(it).path("type").asText() }).containsExactly(
                "response.output_audio_transcript.done",
                "conversation.item.input_audio_transcription.completed",
            )
            assertThat(lifecycle.map { mapper.readTree(it).path("type").asText() }).containsExactly(
                VoiceTutorRealtimeContract.SPOKEN_LESSON_END_EVENT,
            )
            assertThat(controller.acceptsInputEvents()).isFalse()
            relay.dispose()
        } finally {
            releaseTutorPersistence.countDown()
            controller.close()
            subscriptions.forEach(Disposable::dispose)
        }
    }

    @Test
    fun `due continuous speech checkpoint fires immediately after persisted boundary ack`() {
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
        )
        val controls = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val output = controller.providerEvents().subscribe(controls::add, errors::add)
        val proofStarted = CountDownLatch(1)
        val releaseProof = CountDownLatch(1)
        val relayFinished = CountDownLatch(1)
        try {
            controller.startOpeningResponse()
            val opening = controls.single()
            controller.observeProviderEvent(providerResponseEvent("response.created", opening))
            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.started"))
            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped"))
            controller.observeClientEvent(
                """{"type":"buddystudy.voice.input.speech.started","sequence":1}""",
            )
            controller.fireContinuousSpeechDeadline()
            assertThat(controls.map { mapper.readTree(it).path("type").asText() })
                .containsExactly("response.create")

            val relay = relayVoiceTutorProviderEvent(
                controller,
                providerResponseEvent("response.done", opening),
            ) { _, _, _ ->
                proofStarted.countDown()
                check(releaseProof.await(5, TimeUnit.SECONDS))
                true
            }.subscribeOn(Schedulers.boundedElastic())
                .doFinally { relayFinished.countDown() }
                .subscribe({}, errors::add)
            assertThat(proofStarted.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(controls.map { mapper.readTree(it).path("type").asText() })
                .containsExactly("response.create")

            releaseProof.countDown()
            assertThat(relayFinished.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(errors).isEmpty()
            assertThat(controls.map { mapper.readTree(it).path("type").asText() })
                .containsExactly("response.create", "input_audio_buffer.commit")
            relay.dispose()
        } finally {
            releaseProof.countDown()
            controller.close()
            output.dispose()
        }
    }

    @Test
    fun `blank tutor final yields an empty batch and response output supplies assessment context`() {
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            inputCoordinator = VoiceTutorInputTurnCoordinator(),
        )
        val controls = CopyOnWriteArrayList<String>()
        val actions = CopyOnWriteArrayList<VoiceTutorInputTurnCoordinator.Action>()
        val subscriptions = listOf(
            controller.providerEvents().subscribe(controls::add),
            controller.inputActions().subscribe(actions::add),
        )
        try {
            controller.startOpeningResponse()
            val opening = controls.single()
            controller.observeProviderEvent(providerResponseEvent("response.created", opening))
            controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.started"))
            controller.observeClientEvent(
                """{"type":"buddystudy.voice.input.speech.started","sequence":1}""",
            )
            controller.observeClientEvent(
                """{"type":"buddystudy.voice.input.speech.stopped","sequence":1}""",
            )
            controller.observeProviderEvent(
                """{"type":"input_audio_buffer.committed","item_id":"learner-fallback"}""",
            )
            controller.observeProviderEvent(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"learner-fallback","transcript":"준비됐어요"}""",
            )
            controller.observeProviderEvent(tutorTranscriptEvent("   "))
            controller.observeProviderEvent(
                providerResponseEvent(
                    "response.done",
                    opening,
                    tutorFallback = "response done fallback context",
                ),
            )
            assertThat(actions).isEmpty()

            val observed = controller.observeProviderEventWithPostRelay(
                outputBufferEvent("output_audio_buffer.stopped"),
            )
            val boundary = observed.postRelayBoundary
            assertThat(boundary).isNotNull
            assertThat(boundary!!.tutorTranscriptEvents).isEmpty()
            assertThat(actions).isEmpty()

            assertThat(controller.acknowledgePostRelayBoundary(boundary.token)).isTrue()
            val assessment = actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Assess>().single()
            assertThat(assessment.teacherContext).isEqualTo("response done fallback context")
        } finally {
            controller.close()
            subscriptions.forEach(Disposable::dispose)
        }
    }

    @Test
    fun `sideband verifies provider configuration before readiness and opening without losing an early learner turn`() {
        val subscriptions = mutableListOf<String>()
        val sent = mutableListOf<String>()
        var readyEmitted = false
        val handshake = VoiceTutorWebRtcSessionHandshake(
            "rtc_opening", Duration.ofSeconds(15), transcriptionLanguage = "ko",
        )
        val providerEvents = Sinks.many().unicast().onBackpressureBuffer<String>()
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
        )
        val receive = providerEvents.asFlux()
            .doOnSubscribe { subscriptions += "receive" }
            .doOnNext { raw ->
                if (!handshake.observeProviderEvent(raw)) controller.observeProviderEvent(raw)
            }
            .then()
        val send = Flux.concat(handshake.initialProviderEvents(), controller.providerEvents())
            .doOnSubscribe { subscriptions += "send" }
            .doOnNext { raw ->
                if (mapper.readTree(raw).path("type").asText() == "response.create") {
                    assertThat(readyEmitted).isTrue()
                }
                sent += raw
            }
            .then()
        val ready = handshake.awaitConfirmation().then(
            Mono.fromRunnable<Void> {
                assertThat(subscriptions).containsExactly("receive", "send")
                readyEmitted = true
                controller.startOpeningResponse()
            },
        )

        val diagnostics = VoiceTutorSidebandDiagnostics("rtc_opening")
        val relay = webRtcSidebandLifecycle(receive, send, ready, diagnostics).subscribe()
        fun emitProvider(raw: String) {
            assertThat(providerEvents.tryEmitNext(raw)).isEqualTo(Sinks.EmitResult.OK)
        }
        val confirmedConfiguration = """{
            "type":"session.updated",
            "session":{"type":"realtime","audio":{"input":{
                "turn_detection":null,
                "transcription":{"model":"gpt-4o-mini-transcribe","language":"ko"}
            }}}
        }""".trimIndent()
        try {
            assertThat(sent).hasSize(1)
            assertThat(mapper.readTree(sent.single()).path("type").asText()).isEqualTo("session.update")
            assertThat(readyEmitted).isFalse()

            emitProvider(confirmedConfiguration.replace("session.updated", "session.created"))
            emitProvider("""{"type":"input_audio_buffer.speech_started"}""")
            emitProvider("""{"type":"input_audio_buffer.speech_stopped"}""")
            emitProvider("""{"type":"input_audio_buffer.committed","item_id":"early-greeting"}""")
            assertThat(readyEmitted).isFalse()
            assertThat(sent).hasSize(1)

            emitProvider(confirmedConfiguration)
            assertThat(readyEmitted).isTrue()
            assertThat(sent).hasSize(2)
            val opening = mapper.readTree(sent.last())
            assertThat(opening.path("type").asText()).isEqualTo("response.create")
            assertThat(opening.path("event_id").asText()).contains("opening-response")
            emitProvider(confirmedConfiguration)
            assertThat(sent).hasSize(2)

            val response = mapOf(
                "id" to "response-opening",
                "status" to "completed",
                "metadata" to opening.path("response").path("metadata"),
            )
            emitProvider(mapper.writeValueAsString(mapOf("type" to "response.created", "response" to response)))
            controller.observeClientEvent("""{"type":"buddystudy.voice.input.speech.started","sequence":1}""")
            controller.observeClientEvent("""{"type":"buddystudy.voice.input.speech.stopped","sequence":1}""")
            assertThat(sent).hasSize(3)
            val commit = mapper.readTree(sent.last())
            assertThat(commit.path("type").asText()).isEqualTo("input_audio_buffer.commit")
            assertThat(commit.path("event_id").asText()).startsWith("buddystudy-internal-")
            emitProvider("""{"type":"input_audio_buffer.committed","item_id":"greeting-after-ready"}""")
            emitProvider(mapper.writeValueAsString(mapOf("type" to "response.done", "response" to response)))
            assertThat(sent).hasSize(3)
            // The owned server buffer must drain; a device PCM-silence ACK is
            // neither injected by this fixture nor needed to release the turn.
            emitProvider("""{"type":"output_audio_buffer.stopped","response_id":"response-opening"}""")
            assertThat(sent).hasSize(4)
            assertThat(mapper.readTree(sent.last()).path("event_id").asText()).contains("turn-response")
            assertThat(sent.joinToString()).doesNotContain(
                "response.cancel", "conversation.item.truncate", "output_audio_buffer.clear",
            )
            assertThat(relay.isDisposed).isFalse()
        } finally {
            relay.dispose()
            controller.close()
        }
    }

    private fun postRelayFixture(transcript: String?): PostRelayFixture {
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(30),
            responseTimeout = Duration.ofSeconds(60),
            transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
        )
        val controls = CopyOnWriteArrayList<String>()
        val lifecycle = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val subscriptions = listOf(
            controller.providerEvents().subscribe(controls::add, errors::add),
            controller.serverLifecycleEvents().subscribe(lifecycle::add, errors::add),
        )
        controller.startOpeningResponse()
        val opening = controls.single { mapper.readTree(it).path("type").asText() == "response.create" }
        controller.observeProviderEvent(providerResponseEvent("response.created", opening))
        controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.started"))
        transcript?.let { controller.observeProviderEvent(tutorTranscriptEvent(it)) }
        controller.observeClientEvent(
            """{"type":"buddystudy.voice.input.speech.started","sequence":1}""",
        )
        controller.observeClientEvent(
            """{"type":"buddystudy.voice.input.speech.stopped","sequence":1}""",
        )
        controller.observeProviderEvent(
            """{"type":"input_audio_buffer.committed","item_id":"queued-learner"}""",
        )
        assertThat(errors).isEmpty()
        return PostRelayFixture(controller, controls, lifecycle, subscriptions, opening)
    }

    private inner class PostRelayFixture(
        val controller: VoiceTutorDuplexTurnController,
        val controls: CopyOnWriteArrayList<String>,
        val lifecycle: CopyOnWriteArrayList<String>,
        private val subscriptions: List<Disposable>,
        private val openingControl: String,
    ) {
        fun responseCreates(): List<String> = controls.filter {
            mapper.readTree(it).path("type").asText() == "response.create"
        }

        fun finalBoundaryEvent(ordering: String): String = when (ordering) {
            "done-then-stopped" -> {
                controller.observeProviderEvent(providerResponseEvent("response.done", openingControl))
                outputBufferEvent("output_audio_buffer.stopped")
            }
            "stopped-then-done" -> {
                controller.observeProviderEvent(outputBufferEvent("output_audio_buffer.stopped"))
                providerResponseEvent("response.done", openingControl)
            }
            else -> error("Unsupported boundary ordering: $ordering")
        }

        fun close() {
            controller.close()
            subscriptions.forEach(Disposable::dispose)
        }
    }

    private fun providerResponseEvent(
        type: String,
        responseControl: String,
        tutorFallback: String? = null,
    ): String {
        val controlMetadata = mapper.readTree(responseControl).path("response").path("metadata")
        val token = controlMetadata.path("buddystudy_response_token").asText()
        val metadata = linkedMapOf<String, Any>("buddystudy_response_token" to token)
        val quotaMarker = controlMetadata.path(VoiceTutorRealtimeContract.QUOTA_NOTICE_METADATA_KEY)
        if (quotaMarker.isBoolean && quotaMarker.booleanValue()) {
            metadata[VoiceTutorRealtimeContract.QUOTA_NOTICE_METADATA_KEY] = true
        }
        val response = linkedMapOf<String, Any>(
            "id" to RESPONSE_ID,
            "status" to if (type == "response.created") "in_progress" else "completed",
            "metadata" to metadata,
        )
        tutorFallback?.let { transcript ->
            response["output"] = listOf(
                mapOf(
                    "role" to "assistant",
                    "content" to listOf(mapOf("transcript" to transcript)),
                ),
            )
        }
        return mapper.writeValueAsString(
            mapOf(
                "type" to type,
                "response" to response,
            ),
        )
    }

    private fun outputBufferEvent(type: String): String =
        """{"type":"$type","response_id":"$RESPONSE_ID"}"""

    private fun tutorTranscriptEvent(
        transcript: String,
        contentIndex: Int = 0,
    ): String = mapper.writeValueAsString(
        mapOf(
            "type" to "response.output_audio_transcript.done",
            "response_id" to RESPONSE_ID,
            "item_id" to TUTOR_ITEM_ID,
            "content_index" to contentIndex,
            "transcript" to transcript,
        ),
    )

    private data class RelayCall(
        val type: String,
        val persist: Boolean,
        val forward: Boolean,
    )

    private fun validSdp(includeDataChannel: Boolean = false): String = buildString {
        append("v=0\r\n")
        append("o=- 1 1 IN IP4 127.0.0.1\r\n")
        append("s=-\r\n")
        append("t=0 0\r\n")
        append("a=ice-ufrag:buddy\r\n")
        append("a=ice-pwd:buddy-study-secret\r\n")
        append("a=fingerprint:$FINGERPRINT\r\n")
        append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
        append("a=mid:0\r\n")
        append("a=rtpmap:111 opus/48000/2\r\n")
        if (includeDataChannel) {
            append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n")
            append("a=mid:1\r\n")
            append("a=sctp-port:5000\r\n")
        }
    }

    private companion object {
        const val RESPONSE_ID = "response-post-relay"
        const val TUTOR_ITEM_ID = "tutor-post-relay"
        val FINGERPRINT = "sha-256 " + (0 until 32).joinToString(":") { "AA" }
    }
}
