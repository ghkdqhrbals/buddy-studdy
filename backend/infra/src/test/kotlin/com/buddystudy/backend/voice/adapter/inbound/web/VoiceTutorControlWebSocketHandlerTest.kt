package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.voice.application.model.VoiceTutorQuotaResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionDetailResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRelayUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorWebRtcUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcAnswer
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.Flow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.HandshakeInfo
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.Proxy
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VoiceTutorControlWebSocketHandlerTest {
    @Test
    fun `provider close delegates one durable WebRTC hangup and finalization operation`() {
        val now = Instant.now()
        val principal = Principal(7, "device-7", 70, anonymous = false)
        val session = activeSession(now)
        val providerRelays = AtomicInteger()
        val hangups = AtomicInteger()
        val finishes = AtomicInteger()
        var finalizedCallId: String? = null
        val receiveCancelled = AtomicBoolean()
        val sent = mutableListOf<String>()
        val webRtc = object : VoiceTutorWebRtcUseCase {
            override suspend fun negotiate(
                principal: Principal,
                sessionId: String,
                offerSdp: String,
            ): VoiceTutorWebRtcAnswer = error("Unexpected negotiation.")

            override suspend fun claimControl(
                principal: Principal,
                sessionId: String,
                connectionId: String,
            ): VoiceTutorWebRtcControlContext = VoiceTutorWebRtcControlContext(session, "rtc_call-1")

            override suspend fun relaySideband(
                callId: String,
                clientEvents: Flow<String>,
                terminalEvents: Flow<VoiceTutorRelayTermination>,
                onProviderEvent: suspend (String, Boolean, Boolean) -> Unit,
            ) {
                providerRelays.incrementAndGet()
                // Simulate a provider that closes normally without waiting for
                // the independently open client receive stream.
            }

            override suspend fun hangup(callId: String) {
                hangups.incrementAndGet()
            }
        }
        val relay = proxy<VoiceTutorRelayUseCase> { method, arguments ->
            when (method) {
                "finishWebRtc" -> {
                    finishes.incrementAndGet()
                    finalizedCallId = arguments[2] as String
                    detail(session, now)
                }
                else -> error("Unexpected VoiceTutorRelayUseCase call: $method")
            }
        }
        val voiceTutor = proxy<VoiceTutorUseCase> { method, _ ->
            error("Unexpected VoiceTutorUseCase call: $method")
        }
        val handler = VoiceTutorControlWebSocketHandler(
            webRtc = webRtc,
            relay = relay,
            voiceTutor = voiceTutor,
            realtimeMetrics = VoiceTutorRealtimeMetrics(SimpleMeterRegistry()),
        )
        val webSocket = webSocket(
            principal = principal,
            receive = Flux.never<WebSocketMessage>().doFinally { receiveCancelled.set(true) },
            sent = sent,
        )

        handler.handle(webSocket).block(Duration.ofSeconds(1))

        assertThat(providerRelays.get()).isEqualTo(1)
        assertThat(receiveCancelled.get()).isTrue()
        assertThat(hangups.get()).isZero()
        assertThat(finishes.get()).isEqualTo(1)
        assertThat(finalizedCallId).isEqualTo("rtc_call-1")
        assertThat(sent.map { JsonType.type(it) }).contains("buddystudy.voice.session.ended")
    }

    private fun webSocket(
        principal: Principal,
        receive: Flux<WebSocketMessage>,
        sent: MutableList<String>,
    ): WebSocketSession {
        val buffers = DefaultDataBufferFactory.sharedInstance
        val authentication = UsernamePasswordAuthenticationToken.authenticated(principal, "", emptyList())
        val handshake = HandshakeInfo(
            URI.create("https://api.example.test/api/v1/voice-tutor/sessions/$SESSION_ID/control"),
            HttpHeaders(),
            Mono.just(authentication),
            VoiceTutorControlWebSocketHandler.CONTROL_PROTOCOL,
        )
        return Proxy.newProxyInstance(
            WebSocketSession::class.java.classLoader,
            arrayOf(WebSocketSession::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getId" -> "control-test"
                "getHandshakeInfo" -> handshake
                "bufferFactory" -> buffers
                "getAttributes" -> mutableMapOf<String, Any>()
                "receive" -> receive
                "send" -> Flux.from(arguments!![0] as Publisher<*>)
                    .cast(WebSocketMessage::class.java)
                    .doOnNext { sent += it.payloadAsText }
                    .then()
                "isOpen" -> true
                "close" -> Mono.empty<Void>()
                "closeStatus" -> Mono.empty<CloseStatus>()
                "textMessage" -> WebSocketMessage(
                    WebSocketMessage.Type.TEXT,
                    buffers.wrap((arguments!![0] as String).toByteArray()),
                )
                "toString" -> "VoiceTutorControlWebSocketTestSession"
                "hashCode" -> 1
                "equals" -> false
                else -> error("Unexpected WebSocketSession call: ${method.name}")
            }
        } as WebSocketSession
    }

    private fun activeSession(now: Instant) = VoiceTutorSession(
        id = SESSION_ID,
        userId = 7,
        studyId = 42,
        idempotencyKey = "control-close-test",
        providerSessionId = "rtc_call-1",
        status = VoiceTutorSessionStatus.ACTIVE,
        resultStatus = VoiceTutorResultStatus.PENDING,
        language = "ko",
        model = "gpt-realtime-test",
        voice = "marin",
        topic = "calculus",
        difficulty = 3,
        periodStartedAt = now,
        periodEndsAt = now.plusSeconds(3_600),
        reservedSeconds = 3_600,
        chargedSeconds = 0,
        maxSessionSeconds = 3_600,
        hardEndsAt = now.plusSeconds(3_600),
        connectedAt = now,
        relayHeartbeatAt = now,
        acceptedAudioBytes = 0,
        endedAt = null,
        finalizedAt = null,
        endReason = null,
        failureCode = null,
        failureMessage = null,
        createdAt = now,
        updatedAt = now,
    )

    private fun detail(session: VoiceTutorSession, now: Instant) = VoiceTutorSessionDetailResponse(
        sessionId = session.id,
        studyId = session.studyId,
        topic = session.topic,
        difficulty = session.difficulty,
        language = session.language,
        state = VoiceTutorSessionStatus.COMPLETED,
        resultStatus = VoiceTutorResultStatus.PENDING,
        createdAt = session.createdAt,
        connectedAt = session.connectedAt,
        endedAt = now,
        hardEndsAt = session.hardEndsAt,
        durationSeconds = 0,
        chargedSeconds = 0,
        quota = VoiceTutorQuotaResponse(
            tierCode = "TIER2",
            periodStartedAt = now,
            resetAt = now.plusSeconds(86_400),
            limitSeconds = 3_600,
            usedSeconds = 0,
            reservedSeconds = 0,
            remainingSeconds = 3_600,
        ),
        transcriptTurns = emptyList(),
        result = null,
        recording = VoiceTutorRecordingResponse(
            enabled = false,
            available = false,
            status = null,
            retentionDays = 0,
            expiresAt = null,
            recordingId = null,
            contentType = null,
            contentLength = null,
            durationSeconds = null,
        ),
    )

    private inline fun <reified T : Any> proxy(
        crossinline invocation: (String, Array<out Any?>) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "${T::class.simpleName}TestProxy"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> invocation(method.name, arguments ?: emptyArray())
        }
    } as T

    private object JsonType {
        fun type(raw: String): String = runCatching {
            com.buddystudy.backend.common.application.json.JsonMapperProvider.mapper
                .readTree(raw).path("type").asText()
        }.getOrDefault("")
    }

    private companion object {
        const val SESSION_ID = "00000000-0000-0000-0000-000000000001"
    }
}
