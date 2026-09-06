package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorCreateSessionResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorQuotaResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRelayContext
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionDetailResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorStatusResponse
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRelayUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** In-process fallback lifecycle only; no provider, audio, credential or network use. */
class VoiceTutorLegacySpokenLessonEndTest {
    @Test
    fun `legacy handler accepts only the internal end signal and delivers completed event before socket close`(): Unit {
        val now = Instant.now()
        val principal = Principal(7, "device-7", 70, anonymous = false)
        val session = activeSession(now)
        val finishCalls = AtomicInteger()
        val finishReasons = CopyOnWriteArrayList<String>()
        val delivery = CopyOnWriteArrayList<String>()
        val relay = object : VoiceTutorRelayUseCase {
            override suspend fun connect(principal: Principal, sessionId: String) =
                VoiceTutorRelayContext(session, "synthetic")

            override suspend fun relayAuthorized(principal: Principal) = true
            override suspend fun attachProviderSession(principal: Principal, sessionId: String, providerSessionId: String) = Unit
            override suspend fun heartbeat(principal: Principal, sessionId: String) = VoiceTutorSessionStatus.ACTIVE
            override suspend fun recordAcceptedAudioBytes(principal: Principal, sessionId: String, bytes: Long) = Unit
            override suspend fun relayState(principal: Principal, sessionId: String) = VoiceTutorSessionStatus.ACTIVE

            override suspend fun relayProvider(
                principal: Principal,
                context: VoiceTutorRelayContext,
                clientEvents: Flow<String>,
                terminalEvents: Flow<VoiceTutorRelayTermination>,
                onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
            ) {
                assertThat(onProviderEvent(
                    JsonMapperProvider.mapper.writeValueAsString(
                        mapOf("type" to VoiceTutorRealtimeContract.SPOKEN_LESSON_END_EVENT),
                    ),
                    false,
                    false,
                )).isFalse()
                assertThat(terminalEvents.first().cancelActiveResponse).isFalse()
                throw IllegalStateException("Provider closed after the user end signal.")
            }

            override suspend fun appendTranscript(
                principal: Principal,
                sessionId: String,
                providerItemId: String,
                role: VoiceTutorTranscriptRole,
                transcript: String,
                occurredAt: Instant,
                lessonRevision: Long,
                studyQuestionProviderItemId: String?,
                studyAnswerProviderItemId: String?,
                askedStudyQuestion: Boolean,
                isStudyQuestion: Boolean,
                studyAnswerProviderItemIds: List<String>,
                acceptedBeforeQuotaCutoff: Boolean,
                postCallEvidence: Boolean,
                conversationSequence: Long?,
            ): Boolean = error("Internal end must not be persisted as transcript.")

            override suspend fun finish(
                principal: Principal,
                sessionId: String,
                reason: String,
                failed: Boolean,
                failureMessage: String?,
            ): VoiceTutorSessionDetailResponse {
                assertThat(failed).isFalse()
                assertThat(failureMessage).isNull()
                finishCalls.incrementAndGet()
                finishReasons += reason
                return detail(session, now)
            }

            override suspend fun finishWebRtc(
                principal: Principal,
                sessionId: String,
                providerSessionId: String,
                reason: String,
                failed: Boolean,
                failureMessage: String?,
            ): VoiceTutorSessionDetailResponse = error("Legacy handler must use finish().")
        }
        val status = VoiceTutorStatusResponse(
            eligible = true, reason = null, tierCode = "TIER2", quota = detail(session, now).quota,
            maxSessionSeconds = 3_600, recording = detail(session, now).recording, activeSession = null,
        )
        val voiceTutor = proxy<VoiceTutorUseCase> { method ->
            if (method == "status") status else error("Unexpected VoiceTutorUseCase call: $method")
        }
        val socket = webSocket(principal, delivery)

        VoiceTutorWebSocketHandler(relay, voiceTutor).handle(socket).block(Duration.ofSeconds(2))

        assertThat(finishCalls).hasValue(1)
        assertThat(finishReasons).containsExactly("USER_ENDED")
        assertThat(delivery).containsSubsequence(
            "sent:buddystudy.voice.session.ended", "close",
        )
        assertThat(delivery).doesNotContain("sent:${VoiceTutorRealtimeContract.SPOKEN_LESSON_END_EVENT}")
    }

    private fun webSocket(principal: Principal, delivery: MutableList<String>): WebSocketSession {
        val buffers = DefaultDataBufferFactory.sharedInstance
        val authentication = UsernamePasswordAuthenticationToken.authenticated(principal, "", emptyList())
        val handshake = HandshakeInfo(
            URI.create("https://api.example.test/api/v1/voice-tutor/sessions/$SESSION_ID/stream"),
            HttpHeaders(), Mono.just(authentication), VoiceTutorCreateSessionResponse.WEBSOCKET_PROTOCOL,
        )
        return Proxy.newProxyInstance(
            WebSocketSession::class.java.classLoader, arrayOf(WebSocketSession::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getId" -> "legacy-spoken-end"
                "getHandshakeInfo" -> handshake
                "bufferFactory" -> buffers
                "getAttributes" -> mutableMapOf<String, Any>()
                "receive" -> Flux.never<WebSocketMessage>()
                "send" -> Flux.from(arguments!![0] as Publisher<*>).cast(WebSocketMessage::class.java)
                    .doOnNext {
                        delivery += "sent:${JsonMapperProvider.mapper.readTree(it.payloadAsText).path("type").asText()}"
                    }.then()
                "isOpen" -> true
                "close" -> Mono.fromRunnable<Void> { delivery += "close" }
                "closeStatus" -> Mono.empty<CloseStatus>()
                "textMessage" -> WebSocketMessage(
                    WebSocketMessage.Type.TEXT,
                    buffers.wrap((arguments!![0] as String).toByteArray()),
                )
                "toString" -> "LegacySpokenEndWebSocket"
                "hashCode" -> 1
                "equals" -> false
                else -> error("Unexpected WebSocketSession call: ${method.name}")
            }
        } as WebSocketSession
    }

    private fun activeSession(now: Instant) = VoiceTutorSession(
        id = SESSION_ID, userId = 7, studyId = 42, idempotencyKey = "legacy-spoken-end",
        providerSessionId = "provider-session", status = VoiceTutorSessionStatus.ACTIVE,
        resultStatus = VoiceTutorResultStatus.PENDING, language = "ko", model = "gpt-realtime-test",
        voice = "marin", topic = "Redis", difficulty = 3, periodStartedAt = now,
        periodEndsAt = now.plusSeconds(3_600), reservedSeconds = 3_600, chargedSeconds = 0,
        maxSessionSeconds = 3_600, hardEndsAt = now.plusSeconds(3_600), connectedAt = now,
        relayHeartbeatAt = now, acceptedAudioBytes = 0, endedAt = null, finalizedAt = null,
        endReason = null, failureCode = null, failureMessage = null, createdAt = now, updatedAt = now,
    )

    private fun detail(session: VoiceTutorSession, now: Instant) = VoiceTutorSessionDetailResponse(
        sessionId = session.id, studyId = session.studyId, topic = session.topic,
        difficulty = session.difficulty, language = session.language, state = VoiceTutorSessionStatus.COMPLETED,
        resultStatus = VoiceTutorResultStatus.PENDING, createdAt = session.createdAt,
        connectedAt = session.connectedAt, endedAt = now, hardEndsAt = session.hardEndsAt,
        durationSeconds = 0, chargedSeconds = 0,
        quota = VoiceTutorQuotaResponse(
            tierCode = "TIER2", periodStartedAt = now, resetAt = now.plusSeconds(86_400),
            limitSeconds = 3_600, usedSeconds = 0, reservedSeconds = 0, remainingSeconds = 3_600,
        ),
        transcriptTurns = emptyList(), result = null,
        recording = VoiceTutorRecordingResponse(
            enabled = false, available = false, status = null, retentionDays = 0, expiresAt = null,
            recordingId = null, contentType = null, contentLength = null, durationSeconds = null,
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> proxy(crossinline invocation: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "${T::class.simpleName}TestProxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> invocation(method.name)
            }
        } as T

    private companion object {
        const val SESSION_ID = "00000000-0000-0000-0000-000000000002"
    }
}
