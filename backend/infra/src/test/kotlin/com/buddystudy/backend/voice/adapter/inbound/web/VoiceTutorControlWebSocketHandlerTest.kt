package com.buddystudy.backend.voice.adapter.inbound.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorSidebandBranch
import com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorUnexpectedSidebandCloseException
import com.buddystudy.backend.voice.application.model.VoiceTutorQuotaResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorRecordingResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorSessionDetailResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorStatusResponse
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.HandshakeInfo
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.lang.reflect.Proxy
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VoiceTutorControlWebSocketHandlerTest {
    @Test
    fun `missing or unsupported local vad capability closes before claiming the provider call`() {
        listOf(null, "", "local-vad-v0").forEach { capability ->
            val closed = mutableListOf<CloseStatus>()
            val handler = VoiceTutorControlWebSocketHandler(
                proxy<VoiceTutorWebRtcUseCase> { _, _ -> error("Must not claim or open a provider call.") },
                proxy<VoiceTutorRelayUseCase> { _, _ -> error("Must not mutate a session.") },
                proxy<VoiceTutorUseCase> { _, _ -> error("Must not mutate quota.") },
                VoiceTutorRealtimeMetrics(SimpleMeterRegistry()),
            )
            handler.handle(webSocket(
                Principal(7, "device-7", 70, anonymous = false), Flux.never(), mutableListOf(),
                turnProtocol = capability, onClose = { closed += it },
            )).block(Duration.ofSeconds(1))
            assertThat(closed).containsExactly(CloseStatus.PROTOCOL_ERROR)
        }
    }

    @Test
    fun `speech activity cannot reach the provider before confirmed media readiness`() {
        val result = runControlScenario(
            clientPayload = """{"type":"${VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT}","sequence":1}""",
            provider = { _, terminal -> terminal.first() },
        )
        assertThat(result.failed).isTrue()
        assertThat(result.reason).isEqualTo("CLIENT_PROTOCOL_ERROR")
    }

    @Test
    fun `ready local speech pairs reach only the server turn controller without extra client fields`() {
        val received = mutableListOf<String>()
        val types = listOf(VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT, VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT)
        runControlScenario(
            clientAfterReady = types.map { """{"type":"$it","sequence":1,"private":"PRIVATE_PAYLOAD"}""" },
            provider = { events, _ -> received += events.take(2).toList() },
        )
        assertThat(received).hasSize(2)
        received.zip(types).forEach { (raw, type) ->
            val node = com.buddystudy.backend.common.application.json.JsonMapperProvider.mapper.readTree(raw)
            assertThat(node.path("type").asText()).isEqualTo(type)
            assertThat(node.path("sequence").longValue()).isEqualTo(1)
            assertThat(node.size()).isEqualTo(2)
            assertThat(raw).doesNotContain("PRIVATE_PAYLOAD", "input_audio_buffer.commit")
        }
    }

    @Test
    fun `provider cleared event stays failed when terminal send wins before receive throws`() {
        withControlLogs { logs ->
            val result = runControlScenario(
                providerEventBeforeCompletion = """{"type":"output_audio_buffer.cleared","response_id":"private-provider-response"}""",
                provider = { _, _ -> },
            )

            assertThat(result.failed).isTrue()
            assertThat(result.reason).isEqualTo("PROVIDER_ERROR")
            assertThat(logs.list.map { it.formattedMessage }).anySatisfy {
                assertThat(it).contains(
                    "voice_tutor_control_terminal", "source=PROVIDER_EVENT_ERROR",
                    "reason=PROVIDER_ERROR", "errorType=VoiceTutorProviderReportedException",
                )
            }
            assertThat(logs.list.map { it.formattedMessage }.joinToString("\n"))
                .doesNotContain("private-provider-response")
        }
    }

    @Test
    fun `unexpected provider error is failed and diagnostics omit raw call IDs and exception messages`() {
        withControlLogs { logs ->
            val result = runControlScenario(
                provider = { _, _ -> throw IllegalStateException("PRIVATE_TRANSCRIPT_AND_TOKEN") },
                closeStatus = Mono.just(CloseStatus(1011, "PRIVATE_CLOSE_REASON")),
            )

            assertThat(result.failed).isTrue()
            assertThat(result.reason).isEqualTo("PROVIDER_ERROR")
            assertThat(result.closeObserverDisposed.get()).isTrue()
            val messages = logs.list.map { it.formattedMessage }
            assertThat(messages).anySatisfy {
                assertThat(it).contains("voice_tutor_control_terminal", "source=PROVIDER_RELAY_ERROR")
            }
            assertThat(messages).anySatisfy {
                assertThat(it).contains("voice_tutor_control_closed", "closeCode=1011")
            }
            assertThat(messages.joinToString("\n"))
                .contains("sessionId=$SESSION_ID", "callRef=", "errorType=IllegalStateException")
                .doesNotContain("rtc_call-1", "PRIVATE_TRANSCRIPT_AND_TOKEN", "PRIVATE_CLOSE_REASON")
            assertThat(logs.list).allSatisfy { assertThat(it.throwableProxy).isNull() }
        }
    }

    @Test
    fun `client end is first terminal source and a missing close frame cannot delay finalization`() {
        withControlLogs { logs ->
            val result = runControlScenario(
                clientPayload = """{"type":"buddystudy.voice.session.end"}""",
                provider = { _, terminal -> terminal.first() },
                closeStatus = Mono.never(),
            )

            assertThat(result.failed).isFalse()
            assertThat(result.reason).isEqualTo("USER_ENDED")
            assertThat(result.closeObserverDisposed.get()).isTrue()
            val terminals = logs.list.map { it.formattedMessage }
                .filter { it.startsWith("voice_tutor_control_terminal ") }
            assertThat(terminals).hasSize(1)
            assertThat(terminals.single()).contains("source=CLIENT_END", "reason=USER_ENDED")
        }
    }

    @Test
    fun `server verified spoken end uses the same graceful user end finalization exactly once`() {
        withControlLogs { logs ->
            val result = runControlScenario(
                serverLifecycleEventBeforeCompletion =
                    """{"type":"${VoiceTutorRealtimeContract.SPOKEN_LESSON_END_EVENT}"}""",
                provider = { _, terminal ->
                    assertThat(terminal.first().cancelActiveResponse).isFalse()
                },
                closeStatus = Mono.never(),
            )

            assertThat(result.failed).isFalse()
            assertThat(result.reason).isEqualTo("USER_ENDED")
            assertThat(result.finishCalls).isEqualTo(1)
            assertThat(result.closeObserverDisposed.get()).isTrue()
            assertThat(result.deliveryOrder).containsSubsequence(
                "sent:buddystudy.voice.session.ended", "close",
            )
            assertThat(logs.list.map { it.formattedMessage }.filter {
                it.startsWith("voice_tutor_control_terminal ")
            }).singleElement().asString().contains(
                "source=LEARNER_SPOKEN_END", "reason=USER_ENDED", "errorType=none",
            )
        }
    }

    @Test
    fun `provider close after explicit client end does not turn a finished lesson into a failure`() {
        listOf(1000, 1006, null).forEach { code ->
            withControlLogs { logs ->
                val result = runControlScenario(
                    clientPayload = """{"type":"buddystudy.voice.session.end"}""",
                    provider = { _, terminal ->
                        assertThat(terminal.first().cancelActiveResponse).isTrue()
                        throw VoiceTutorUnexpectedSidebandCloseException(VoiceTutorSidebandBranch.RECEIVE, code)
                    },
                    closeStatus = Mono.never(),
                )

                assertThat(result.failed).isFalse()
                assertThat(result.reason).isEqualTo("USER_ENDED")
                assertThat(result.finishCalls).isEqualTo(1)
                assertThat(result.closeObserverDisposed.get()).isTrue()
                val messages = logs.list.map { it.formattedMessage }
                assertThat(messages.filter { it.startsWith("voice_tutor_control_terminal ") })
                    .singleElement().asString().contains("source=CLIENT_END", "reason=USER_ENDED", "errorType=none")
                assertThat(messages.none { it.startsWith("voice_tutor_webrtc_sideband_failed ") }).isTrue()
            }
        }
    }

    @Test
    fun `a provider close before any local end remains a failed call`() {
        val result = runControlScenario(
            provider = { _, _ -> throw VoiceTutorUnexpectedSidebandCloseException(VoiceTutorSidebandBranch.RECEIVE, 1006) },
        )
        assertThat(result.failed).isTrue()
        assertThat(result.reason).isEqualTo("PROVIDER_ERROR")
        assertThat(result.finishCalls).isEqualTo(1)
    }

    @Test
    fun `a client end triggered by a provider error notification cannot overwrite that earlier error`() {
        withControlLogs { logs ->
            val result = runControlScenario(
                providerEventBeforeCompletion = """{"type":"output_audio_buffer.cleared","response_id":"private-error-response"}""",
                clientEndsOnProviderError = true,
                provider = { _, _ -> },
            )
            assertThat(result.failed).isTrue()
            assertThat(result.reason).isEqualTo("PROVIDER_ERROR")
            assertThat(result.finishCalls).isEqualTo(1)
            assertThat(result.clientEndAfterErrorSent).isTrue()
            assertThat(logs.list.map { it.formattedMessage }.filter { it.startsWith("voice_tutor_control_terminal ") })
                .singleElement().asString().contains("source=PROVIDER_EVENT_ERROR", "reason=PROVIDER_ERROR")
        }
    }

    @Test
    fun `client receive completion is distinguishable from provider completion`() {
        withControlLogs { logs ->
            val result = runControlScenario(
                clientCompletes = true,
                provider = { _, terminal -> terminal.first() },
            )

            assertThat(result.reason).isEqualTo("CLIENT_DISCONNECTED")
            assertThat(logs.list.map { it.formattedMessage }).anySatisfy {
                assertThat(it).contains("voice_tutor_control_terminal", "source=CLIENT_RECEIVE_COMPLETE")
            }
        }
    }

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
                context: VoiceTutorWebRtcControlContext,
                clientEvents: Flow<String>,
                terminalEvents: Flow<VoiceTutorRelayTermination>,
                onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
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
        closeStatus: Mono<CloseStatus> = Mono.empty(),
        turnProtocol: String? = VoiceTutorRealtimeContract.LOCAL_VAD_TURN_PROTOCOL,
        onClose: (CloseStatus) -> Unit = {},
        onServerMessage: (String) -> Unit = {},
    ): WebSocketSession {
        val buffers = DefaultDataBufferFactory.sharedInstance
        val authentication = UsernamePasswordAuthenticationToken.authenticated(principal, "", emptyList())
        val handshake = HandshakeInfo(
            URI.create("https://api.example.test/api/v1/voice-tutor/sessions/$SESSION_ID/control"),
            HttpHeaders().apply {
                if (turnProtocol != null) set(VoiceTutorRealtimeContract.TURN_PROTOCOL_HEADER, turnProtocol)
            },
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
                    .doOnNext {
                        sent += it.payloadAsText
                        onServerMessage(it.payloadAsText)
                    }
                    .then()
                "isOpen" -> true
                "close" -> {
                    onClose(arguments?.firstOrNull() as? CloseStatus ?: CloseStatus.NORMAL)
                    Mono.empty<Void>()
                }
                "closeStatus" -> closeStatus
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

    private class ControlResult {
        var reason: String? = null
        var failed: Boolean? = null
        var finishCalls = 0
        var clientEndAfterErrorSent = false
        val closeObserverDisposed = AtomicBoolean()
        val deliveryOrder = CopyOnWriteArrayList<String>()
    }

    private fun runControlScenario(
        clientPayload: String? = null,
        clientCompletes: Boolean = false,
        closeStatus: Mono<CloseStatus> = Mono.empty(),
        providerEventBeforeCompletion: String? = null,
        serverLifecycleEventBeforeCompletion: String? = null,
        clientAfterReady: List<String> = emptyList(),
        clientEndsOnProviderError: Boolean = false,
        provider: suspend (Flow<String>, Flow<VoiceTutorRelayTermination>) -> Unit,
    ): ControlResult {
        val now = Instant.now()
        val principal = Principal(7, "device-7", 70, anonymous = false)
        val session = activeSession(now)
        val result = ControlResult()
        val afterReady = Sinks.many().unicast().onBackpressureBuffer<WebSocketMessage>()
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
            ) = VoiceTutorWebRtcControlContext(session, "rtc_call-1")

            override suspend fun relaySideband(
                context: VoiceTutorWebRtcControlContext,
                clientEvents: Flow<String>,
                terminalEvents: Flow<VoiceTutorRelayTermination>,
                onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
            ) {
                if (clientAfterReady.isNotEmpty()) {
                    onProviderEvent("""{"type":"${VoiceTutorRealtimeContract.SIDEBAND_READY_EVENT}"}""", false, false)
                    clientAfterReady.forEach { raw ->
                        assertThat(afterReady.tryEmitNext(WebSocketMessage(
                            WebSocketMessage.Type.TEXT,
                            DefaultDataBufferFactory.sharedInstance.wrap(raw.toByteArray()),
                        ))).isEqualTo(Sinks.EmitResult.OK)
                    }
                }
                if (providerEventBeforeCompletion != null) {
                    try {
                        onProviderEvent(providerEventBeforeCompletion, false, true)
                    } catch (_: VoiceTutorProviderReportedException) {
                        // Model the actual firstWithSignal race: terminal send
                        // completes normally while receive is being cancelled,
                        // so its provider error never escapes relaySideband.
                    }
                }
                if (serverLifecycleEventBeforeCompletion != null) {
                    onProviderEvent(serverLifecycleEventBeforeCompletion, false, false)
                }
                provider(clientEvents, terminalEvents)
            }

            override suspend fun hangup(callId: String) = error("Finalization owns hangup.")
        }
        val relay = proxy<VoiceTutorRelayUseCase> { method, arguments ->
            when (method) {
                "finishWebRtc" -> {
                    result.finishCalls += 1
                    result.reason = arguments[3] as String
                    result.failed = arguments[4] as Boolean
                    detail(session, now)
                }
                else -> error("Unexpected VoiceTutorRelayUseCase call: $method")
            }
        }
        val voiceTutor = proxy<VoiceTutorUseCase> { method, _ ->
            when (method) {
                "status" -> VoiceTutorStatusResponse(
                    eligible = true, reason = null, tierCode = "TIER2", quota = detail(session, now).quota,
                    maxSessionSeconds = 3_600, recording = detail(session, now).recording, activeSession = null,
                )
                else -> error("Unexpected VoiceTutorUseCase call: $method")
            }
        }
        val messages = if (clientAfterReady.isNotEmpty() || clientEndsOnProviderError) afterReady.asFlux() else clientPayload?.let {
            Flux.just(
                WebSocketMessage(
                    WebSocketMessage.Type.TEXT,
                    DefaultDataBufferFactory.sharedInstance.wrap(it.toByteArray()),
                ),
            ).concatWith(Flux.never())
        } ?: if (clientCompletes) Flux.empty() else Flux.never()
        val socket = webSocket(
            principal, messages, mutableListOf(),
            closeStatus.doFinally { result.closeObserverDisposed.set(true) },
            onClose = { result.deliveryOrder += "close" },
            onServerMessage = { raw ->
                result.deliveryOrder += "sent:${JsonType.type(raw)}"
                if (clientEndsOnProviderError && JsonType.type(raw) == "buddystudy.voice.error") {
                    val emitted = afterReady.tryEmitNext(WebSocketMessage(
                        WebSocketMessage.Type.TEXT,
                        DefaultDataBufferFactory.sharedInstance.wrap("""{"type":"buddystudy.voice.session.end"}""".toByteArray()),
                    ))
                    assertThat(emitted).isEqualTo(Sinks.EmitResult.OK)
                    result.clientEndAfterErrorSent = true
                }
            },
        )
        VoiceTutorControlWebSocketHandler(
            webRtc, relay, voiceTutor, VoiceTutorRealtimeMetrics(SimpleMeterRegistry()),
        ).handle(socket).block(Duration.ofSeconds(2))
        return result
    }

    private fun withControlLogs(test: (ListAppender<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(VoiceTutorControlWebSocketHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also {
            // Handler completion and its final diagnostic can race on different
            // coroutine workers; the test must not iterate Logback's ArrayList
            // while that final event is appended.
            it.list = CopyOnWriteArrayList()
            it.start()
        }
        logger.addAppender(appender)
        try {
            test(appender)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
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
