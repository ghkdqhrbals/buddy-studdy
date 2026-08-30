package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRelayUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorWebRtcUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.util.concurrent.Queues
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Component
class VoiceTutorControlWebSocketHandler(
    private val webRtc: VoiceTutorWebRtcUseCase,
    private val relay: VoiceTutorRelayUseCase,
    private val voiceTutor: VoiceTutorUseCase,
    private val realtimeMetrics: VoiceTutorRealtimeMetrics,
) : WebSocketHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapperProvider.mapper
    private val providerPolicy = VoiceTutorRealtimeEventPolicy(mapper)

    override fun getSubProtocols(): List<String> = listOf(CONTROL_PROTOCOL)

    override fun handle(session: WebSocketSession): Mono<Void> {
        if (session.handshakeInfo.subProtocol != CONTROL_PROTOCOL) {
            return session.close(CloseStatus.PROTOCOL_ERROR)
        }
        return session.handshakeInfo.principal
            .cast(Authentication::class.java)
            .switchIfEmpty(Mono.error(IllegalStateException("Authenticated Voice Tutor principal is missing.")))
            .flatMap { authentication ->
                val principal = authentication.principal as? Principal
                    ?: return@flatMap Mono.error(IllegalStateException("Voice Tutor principal is invalid."))
                val sessionId = session.handshakeInfo.uri.path.substringBeforeLast("/control").substringAfterLast('/')
                mono { webRtc.claimControl(principal, sessionId, UUID.randomUUID().toString()) }
                    .flatMap { context -> bridge(session, principal, context) }
            }
    }

    private fun bridge(
        clientSession: WebSocketSession,
        principal: Principal,
        context: com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext,
    ): Mono<Void> {
        val sessionId = context.session.id
        val callId = context.callId
        val endReason = AtomicReference("PROVIDER_CLOSED")
        val failure = AtomicReference<Throwable?>(null)
        val userEnded = AtomicBoolean(false)
        val sidebandReady = AtomicBoolean(false)
        val latency = realtimeMetrics.webRtcTracker()
        val clientTraffic = VoiceTutorClientTrafficGuard(
            providerPolicy,
            maxSessionSeconds = context.session.maxSessionSeconds,
        )
        val outgoing = Sinks.many().unicast()
            .onBackpressureBuffer(Queues.get<String>(MAX_OUTGOING_EVENTS).get())
        val providerControlEvents = Sinks.many().unicast()
            .onBackpressureBuffer(Queues.get<String>(MAX_PENDING_CLIENT_CONTROLS).get())
        val terminalSignal = Sinks.one<VoiceTutorRelayTermination>()
        val relayTerminated = AtomicBoolean(false)
        val terminalGate = Any()

        fun signalTerminal(cancelActiveResponse: Boolean) {
            synchronized(terminalGate) {
                if (relayTerminated.compareAndSet(false, true)) {
                    terminalSignal.tryEmitValue(VoiceTutorRelayTermination(cancelActiveResponse))
                }
            }
        }

        fun emitProviderPayload(value: String) {
            synchronized(terminalGate) {
                if (!relayTerminated.get()) emitRequired(outgoing, value)
            }
        }

        val sendToClient = clientSession.send(outgoing.asFlux().map(clientSession::textMessage))
            .doOnError { error ->
                if (error is IllegalStateException) {
                    failure.compareAndSet(null, error)
                    endReason.compareAndSet("PROVIDER_CLOSED", "CONTROL_BACKPRESSURE")
                } else {
                    endReason.compareAndSet("PROVIDER_CLOSED", "CLIENT_DISCONNECTED")
                }
                signalTerminal(cancelActiveResponse = true)
            }
            .onErrorResume { Mono.empty() }
        val untilDeadline = Duration.between(Instant.now(), context.session.hardEndsAt).coerceAtLeast(Duration.ZERO)
        val deadline = Mono.delay(untilDeadline).map {
            endReason.set("TIME_LIMIT")
            signalTerminal(cancelActiveResponse = true)
            emit(
                outgoing,
                synthetic(
                    "buddystudy.voice.session.ending",
                    sessionId,
                    mapOf("reason" to "TIME_LIMIT", "hardEndsAt" to context.session.hardEndsAt),
                ),
            )
            "TIME_LIMIT"
        }
        val serverControl = Flux.interval(Duration.ofSeconds(2))
            .concatMap {
                mono {
                    pollVoiceTutorRelayControl(
                        authorized = { relay.relayAuthorized(principal) },
                        heartbeat = { relay.heartbeat(principal, sessionId) },
                    )
                }
            }
            .filter { !it.authorized || it.state != VoiceTutorSessionStatus.ACTIVE }
            .next()
            .map { control ->
                val reason = when {
                    !control.authorized -> "AUTH_REVOKED"
                    control.state == VoiceTutorSessionStatus.ENDING -> "USER_ENDED"
                    else -> "SERVER_FINALIZED"
                }
                endReason.set(reason)
                signalTerminal(cancelActiveResponse = true)
                emit(
                    outgoing,
                    synthetic(
                        "buddystudy.voice.session.ending",
                        sessionId,
                        mapOf("reason" to reason, "hardEndsAt" to context.session.hardEndsAt),
                    ),
                )
                reason
            }
        val serverStop = Mono.firstWithSignal(deadline, serverControl)

        val clientInput = clientSession.receive()
            .doOnComplete {
                if (!userEnded.get()) {
                    endReason.compareAndSet("PROVIDER_CLOSED", "CLIENT_DISCONNECTED")
                }
                signalTerminal(cancelActiveResponse = true)
            }
            .map(::voiceTutorClientTextPayload)
            .concatMap { raw ->
                val node = runCatching { mapper.readTree(raw) }.getOrNull()
                    ?: return@concatMap Mono.error<String>(
                        VoiceTutorClientProtocolException("Voice Tutor control event must be valid JSON."),
                    )
                val type = node.path("type").asText()
                val traffic = clientTraffic.inspect(raw)
                when (type) {
                    VoiceTutorRealtimeEventPolicy.CLIENT_HEARTBEAT_EVENT -> {
                        if (traffic.acceptedLocalEvent) {
                            mono { relay.heartbeat(principal, sessionId) }
                                .doOnNext { state ->
                                    emit(
                                        outgoing,
                                        synthetic(
                                            "buddystudy.voice.heartbeat.ack",
                                            sessionId,
                                            mapOf("state" to state.name),
                                        ),
                                    )
                                    if (state != VoiceTutorSessionStatus.ACTIVE) {
                                        endReason.compareAndSet("PROVIDER_CLOSED", "SERVER_FINALIZED")
                                        signalTerminal(cancelActiveResponse = true)
                                    }
                                }
                                .then(Mono.empty<String>())
                        } else {
                            Mono.empty()
                        }
                    }
                    VoiceTutorRealtimeEventPolicy.CLIENT_END_EVENT -> {
                        userEnded.set(true)
                        endReason.set("USER_ENDED")
                        signalTerminal(cancelActiveResponse = true)
                        Mono.empty()
                    }
                    VoiceTutorRealtimeContract.PLAYOUT_DRAINED_EVENT -> {
                        val responseId = node.path("responseId").asText()
                        latency.observeDevicePlayoutDrained(responseId)
                        Mono.just(raw)
                    }
                    else -> Mono.error(
                        VoiceTutorClientProtocolException("Unsupported Voice Tutor WebRTC control event."),
                    )
                }
            }
            .takeUntilOther(serverStop)
            .takeUntilOther(terminalSignal.asMono())
            .doOnNext { raw -> emitClientControl(providerControlEvents, raw) }
            .onErrorResume { error ->
                failure.compareAndSet(null, error)
                endReason.set(
                    if (error is VoiceTutorClientProtocolException) {
                        "CLIENT_PROTOCOL_ERROR"
                    } else {
                        "CONTROL_ERROR"
                    },
                )
                signalTerminal(cancelActiveResponse = true)
                Mono.empty()
            }
            .doFinally { providerControlEvents.tryEmitComplete() }
            .then()

        val providerRelay = mono {
            webRtc.relaySideband(
                callId = callId,
                clientEvents = providerControlEvents.asFlux().asFlow(),
                terminalEvents = terminalSignal.asMono().asFlow(),
            ) { raw, persist, forwardToClient ->
                val type = runCatching { mapper.readTree(raw).path("type").asText() }.getOrDefault("")
                if (type == VoiceTutorRealtimeContract.SIDEBAND_READY_EVENT) {
                    val firstReady = synchronized(terminalGate) {
                        !relayTerminated.get() && sidebandReady.compareAndSet(false, true)
                    }
                    if (firstReady) {
                        emitReady(principal, context.session, ::emitProviderPayload).awaitSingleOrNull()
                    }
                    return@relaySideband
                }
                latency.observeProviderEvent(raw)
                val decision = providerPolicy.providerDecision(
                    raw,
                    sessionId,
                    Instant.now(),
                    VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
                )
                if (decision.terminate) {
                    decision.payload?.let(::emitProviderPayload)
                    signalTerminal(cancelActiveResponse = false)
                    throw VoiceTutorProviderReportedException()
                }
                if (persist) inspectProviderEvent(principal, sessionId, raw).awaitSingleOrNull()
                if (forwardToClient && type !in WEBRTC_MEDIA_EVENTS) {
                    decision.payload?.let(::emitProviderPayload)
                }
            }
        }.then()

        fun cleanupProvider(): Mono<Void> = mono {
            relay.finishWebRtc(
                principal = principal,
                sessionId = sessionId,
                providerSessionId = callId,
                reason = endReason.get(),
                failed = failure.get() != null,
                failureMessage = failure.get()?.let {
                    if (it is VoiceTutorClientProtocolException) {
                        "Voice Tutor client protocol error."
                    } else {
                        "Realtime provider connection failed."
                    }
                },
            )
        }.doOnNext { detail ->
            emit(
                outgoing,
                synthetic(
                    "buddystudy.voice.session.ended",
                    sessionId,
                    mapOf(
                        "reason" to endReason.get(),
                        "endedAt" to detail.endedAt,
                        "durationSeconds" to detail.durationSeconds,
                        "chargedSeconds" to detail.chargedSeconds,
                        "resultStatus" to detail.resultStatus.name,
                        "pollAfterMs" to if (
                            detail.resultStatus == VoiceTutorResultStatus.PENDING ||
                            detail.resultStatus == VoiceTutorResultStatus.PROCESSING
                        ) 3000 else 0,
                    ),
                ),
            )
        }.onErrorResume {
            emit(
                outgoing,
                synthetic(
                    "buddystudy.voice.error",
                    sessionId,
                    mapOf(
                        "code" to "VOICE_TUTOR_FINALIZATION_FAILED",
                        "message" to "Voice Tutor finalization failed.",
                        "retryable" to true,
                    ),
                ),
            )
            Mono.empty()
        }
            .then()

        val providerWork = providerRelay
            .doOnSuccess { signalTerminal(cancelActiveResponse = false) }
            .doOnError { error ->
                failure.compareAndSet(null, error)
                if (endReason.get() == "PROVIDER_CLOSED") endReason.set("PROVIDER_ERROR")
                signalTerminal(cancelActiveResponse = false)
                logger.warn(
                    "voice_tutor_webrtc_sideband_failed sessionId={} errorType={}",
                    sessionId,
                    error.javaClass.simpleName,
                )
            }
        val providerFlow = Mono.usingWhen(
            Mono.just(callId),
            { providerWork },
            { cleanupProvider() },
            { _, _ -> cleanupProvider() },
            {
                endReason.compareAndSet("PROVIDER_CLOSED", "CLIENT_DISCONNECTED")
                signalTerminal(cancelActiveResponse = true)
                cleanupProvider()
            },
        )
            .onErrorResume { Mono.empty() }
            .doFinally { outgoing.tryEmitComplete() }

        return Mono.`when`(sendToClient, clientInput, providerFlow)
            .then(clientSession.close())
            .onErrorResume { clientSession.close(CloseStatus.SERVER_ERROR) }
    }

    private fun emitReady(
        principal: Principal,
        session: com.buddystudy.voice.domain.VoiceTutorSession,
        emitPayload: (String) -> Unit,
    ) = mono { voiceTutor.status(principal) }.doOnNext { status ->
        emitPayload(
            synthetic(
                "buddystudy.voice.session.ready",
                session.id,
                mapOf(
                    "hardEndsAt" to session.hardEndsAt,
                    "quotaRemainingSeconds" to status.quota.remainingSeconds,
                    "transport" to "WEBRTC",
                ),
            ),
        )
        emitPayload(
            synthetic(
                "buddystudy.voice.quota.updated",
                session.id,
                mapOf(
                    "limitSeconds" to status.quota.limitSeconds,
                    "usedSeconds" to status.quota.usedSeconds,
                    "reservedSeconds" to status.quota.reservedSeconds,
                    "remainingSeconds" to status.quota.remainingSeconds,
                    "chargedSeconds" to 0,
                ),
            ),
        )
    }.then()

    private fun inspectProviderEvent(
        principal: Principal,
        sessionId: String,
        raw: String,
    ): Mono<Void> {
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return Mono.empty()
        return when (node.path("type").asText()) {
            "conversation.item.input_audio_transcription.completed" -> appendTranscript(
                principal,
                sessionId,
                node.path("item_id").asText(),
                VoiceTutorTranscriptRole.USER,
                node.path("transcript").asText(),
            )
            "response.output_audio_transcript.done" -> appendTranscript(
                principal,
                sessionId,
                node.path("item_id").asText().ifBlank { node.path("response_id").asText() },
                VoiceTutorTranscriptRole.TUTOR,
                node.path("transcript").asText(),
            )
            else -> Mono.empty()
        }
    }

    private fun appendTranscript(
        principal: Principal,
        sessionId: String,
        providerItemId: String,
        role: VoiceTutorTranscriptRole,
        transcript: String,
    ): Mono<Void> = if (transcript.isBlank()) {
        Mono.empty()
    } else {
        mono {
            relay.appendTranscript(principal, sessionId, providerItemId, role, transcript, Instant.now())
        }.then()
    }

    private fun synthetic(type: String, sessionId: String, fields: Map<String, Any?>): String =
        mapper.writeValueAsString(
            linkedMapOf<String, Any?>(
                "type" to type,
                "sessionId" to sessionId,
                "serverTime" to Instant.now(),
            ) + fields,
        )

    private fun emit(sink: Sinks.Many<String>, value: String) {
        val result = sink.tryEmitNext(value)
        if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
            sink.tryEmitError(IllegalStateException("Voice Tutor client control buffer overflowed."))
        }
    }

    private fun emitRequired(sink: Sinks.Many<String>, value: String) {
        val result = sink.tryEmitNext(value)
        if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
            sink.tryEmitError(IllegalStateException("Voice Tutor client control buffer overflowed."))
            throw IllegalStateException("Voice Tutor client control buffer overflowed.")
        }
        if (result.isFailure && result != Sinks.EmitResult.FAIL_CANCELLED && result != Sinks.EmitResult.FAIL_TERMINATED) {
            throw IllegalStateException("Voice Tutor client control relay rejected an event: $result")
        }
    }

    private fun emitClientControl(sink: Sinks.Many<String>, value: String) {
        val result = sink.tryEmitNext(value)
        if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
            throw VoiceTutorClientProtocolException("Voice Tutor client control buffer overflowed.")
        }
        if (result.isFailure && result != Sinks.EmitResult.FAIL_CANCELLED && result != Sinks.EmitResult.FAIL_TERMINATED) {
            throw VoiceTutorClientProtocolException("Voice Tutor client control relay rejected an event.")
        }
    }

    private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (isNegative) minimum else this

    companion object {
        const val CONTROL_PROTOCOL = "buddystudy.voice.control.v2"
        private const val MAX_OUTGOING_EVENTS = 128
        private const val MAX_PENDING_CLIENT_CONTROLS = 32
        private val WEBRTC_MEDIA_EVENTS = setOf(
            "response.output_audio.delta",
            "response.output_audio.done",
        )
    }
}
