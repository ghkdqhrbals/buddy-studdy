package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration

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

    @Test
    fun `sideband verifies provider configuration before readiness and opening without losing an early learner turn`() {
        val subscriptions = mutableListOf<String>()
        val sent = mutableListOf<String>()
        var readyEmitted = false
        val handshake = VoiceTutorWebRtcSessionHandshake("rtc_opening", Duration.ofSeconds(15))
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
            "session":{"type":"realtime","audio":{"input":{"turn_detection":null}}}
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
        val FINGERPRINT = "sha-256 " + (0 until 32).joinToString(":") { "AA" }
    }
}
