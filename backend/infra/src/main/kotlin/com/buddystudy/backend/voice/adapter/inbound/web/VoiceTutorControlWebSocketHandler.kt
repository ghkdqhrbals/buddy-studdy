package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata
import com.buddystudy.backend.voice.adapter.outbound.openai.voiceTutorCallReference
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
        if (session.handshakeInfo.subProtocol != CONTROL_PROTOCOL ||
            session.handshakeInfo.headers.getFirst(VoiceTutorRealtimeContract.TURN_PROTOCOL_HEADER) !=
            VoiceTutorRealtimeContract.LOCAL_VAD_TURN_PROTOCOL
        ) {
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
        val callRef = voiceTutorCallReference(callId)
        val startedNanos = System.nanoTime()
        val endReason = AtomicReference("PROVIDER_CLOSED")
        val failure = AtomicReference<Throwable?>(null)
        val terminalSource = AtomicReference("NONE")
        val clientCloseCode = AtomicReference<Int?>(null)
        val localCloseInitiated = AtomicBoolean(false)
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

        fun elapsedMs(): Long = (System.nanoTime() - startedNanos).coerceAtLeast(0) / 1_000_000

        fun signalTerminal(
            source: String,
            cancelActiveResponse: Boolean,
            reason: String? = null,
            error: Throwable? = null,
            payload: String? = null,
        ): Boolean = synchronized(terminalGate) {
            if (relayTerminated.compareAndSet(false, true)) {
                // The first terminal decision owns its outcome as well as
                // its source. Closing the other branch can synchronously
                // report 1006/error; that is not a second failed lesson.
                reason?.let(endReason::set)
                error?.let(failure::set)
                terminalSource.set(source)
                // Only internal state and numeric close codes belong here. Do
                // not log provider/client payloads, close reasons, or errors'
                // messages: they can contain private speech or credentials.
                logger.info(
                    "voice_tutor_control_terminal sessionId={} callRef={} source={} reason={} " +
                        "sidebandReady={} cancelActiveResponse={} elapsedMs={} errorType={}",
                    sessionId, callRef, source, endReason.get(), sidebandReady.get(),
                    cancelActiveResponse, elapsedMs(), failure.get()?.javaClass?.simpleName ?: "none",
                )
                payload?.let { emit(outgoing, it) }
                terminalSignal.tryEmitValue(VoiceTutorRelayTermination(cancelActiveResponse))
                true
            } else {
                false
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
                    signalTerminal(
                        "CLIENT_SEND_ERROR", cancelActiveResponse = true,
                        reason = "CONTROL_BACKPRESSURE", error = error,
                    )
                } else {
                    signalTerminal("CLIENT_SEND_ERROR", cancelActiveResponse = true, reason = "CLIENT_DISCONNECTED")
                }
            }
            .onErrorResume { Mono.empty() }
        val untilDeadline = Duration.between(Instant.now(), context.session.hardEndsAt).coerceAtLeast(Duration.ZERO)
        val deadline = Mono.delay(untilDeadline).map {
            signalTerminal("DEADLINE", cancelActiveResponse = true, reason = "TIME_LIMIT")
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
                signalTerminal("SERVER_CONTROL", cancelActiveResponse = true, reason = reason)
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
                signalTerminal("CLIENT_RECEIVE_COMPLETE", cancelActiveResponse = true, reason = "CLIENT_DISCONNECTED")
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
                                        signalTerminal(
                                            "CLIENT_HEARTBEAT_FINALIZED", cancelActiveResponse = true,
                                            reason = "SERVER_FINALIZED",
                                        )
                                    }
                                }
                                .then(Mono.empty<String>())
                        } else {
                            Mono.empty()
                        }
                    }
                    VoiceTutorRealtimeEventPolicy.CLIENT_END_EVENT -> {
                        signalTerminal("CLIENT_END", cancelActiveResponse = true, reason = "USER_ENDED")
                        Mono.empty()
                    }
                    VoiceTutorRealtimeContract.PLAYOUT_DRAINED_EVENT -> {
                        val responseId = node.path("responseId").asText()
                        latency.observeDevicePlayoutDrained(responseId)
                        Mono.just(raw)
                    }
                    VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT,
                    VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT -> {
                        if (!sidebandReady.get()) {
                            Mono.error(VoiceTutorClientProtocolException("Voice Tutor media is not ready."))
                        } else {
                            // Application controls, not provider API events. The
                            // turn controller owns the eventual input commit.
                            Mono.just(mapper.writeValueAsString(mapOf(
                                "type" to type,
                                "sequence" to node.path("sequence").longValue(),
                            )))
                        }
                    }
                    VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT,
                    VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT,
                    VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT,
                    -> {
                        val sequence = node.path("sequence")
                        if (!sidebandReady.get() || !sequence.isIntegralNumber ||
                            !sequence.canConvertToLong() || sequence.longValue() <= 0
                        ) {
                            // Invalid/stale hold commands cannot change the
                            // turn or microphone state, nor tear down a call.
                            Mono.empty()
                        } else {
                            Mono.just(mapper.writeValueAsString(mapOf(
                                "type" to type,
                                "sequence" to sequence.longValue(),
                            )))
                        }
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
                signalTerminal(
                    "CLIENT_RECEIVE_ERROR", cancelActiveResponse = true,
                    reason = if (error is VoiceTutorClientProtocolException) {
                        "CLIENT_PROTOCOL_ERROR"
                    } else {
                        "CONTROL_ERROR"
                    },
                    error = error,
                )
                Mono.empty()
            }
            .doFinally { providerControlEvents.tryEmitComplete() }
            .then()

        val providerRelay = mono {
            webRtc.relaySideband(
                context = context,
                clientEvents = providerControlEvents.asFlux().asFlow(),
                terminalEvents = terminalSignal.asMono().asFlow(),
            ) { raw, persist, forwardToClient ->
                val type = runCatching { mapper.readTree(raw).path("type").asText() }.getOrDefault("")
                if (type == VoiceTutorRealtimeContract.SPOKEN_LESSON_END_EVENT) {
                    // Only the server-side semantic input path emits this type.
                    // It is never client/provider data and follows the exact
                    // explicit-end finalization, settlement and summary path.
                    if (!persist && !forwardToClient) {
                        signalTerminal("LEARNER_SPOKEN_END", cancelActiveResponse = false, reason = "USER_ENDED")
                    }
                    return@relaySideband false
                }
                if (type == VoiceTutorRealtimeContract.SIDEBAND_READY_EVENT) {
                    val firstReady = synchronized(terminalGate) {
                        !relayTerminated.get() && sidebandReady.compareAndSet(false, true)
                    }
                    if (firstReady) {
                        emitReady(principal, context.session, ::emitProviderPayload).awaitSingleOrNull()
                    }
                    return@relaySideband false
                }
                latency.observeProviderEvent(raw)
                val decision = providerPolicy.providerDecision(
                    raw,
                    sessionId,
                    Instant.now(),
                    VoiceTutorProviderTransport.WEBRTC_SIDEBAND,
                )
                if (decision.terminate) {
                    // Emitting terminal can synchronously complete provider
                    // send and cancel this receive branch before its throw is
                    // delivered. Record failure first so that race cannot turn
                    // a provider error/cleared sentence into a successful call.
                    val error = VoiceTutorProviderReportedException()
                    signalTerminal(
                        "PROVIDER_EVENT_ERROR", cancelActiveResponse = false,
                        reason = "PROVIDER_ERROR", error = error, payload = decision.payload,
                    )
                    throw error
                }
                val persisted = persist && inspectProviderEvent(principal, sessionId, raw).awaitSingleOrNull() == true
                if (forwardToClient && type !in WEBRTC_MEDIA_EVENTS) {
                    decision.payload?.let(::emitProviderPayload)
                }
                persisted
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
            .doOnSuccess { signalTerminal("PROVIDER_RELAY_COMPLETE", cancelActiveResponse = false) }
            .doOnError { error ->
                if (signalTerminal(
                        "PROVIDER_RELAY_ERROR", cancelActiveResponse = false,
                        reason = "PROVIDER_ERROR", error = error,
                    )
                ) {
                    logger.warn(
                        "voice_tutor_webrtc_sideband_failed sessionId={} callRef={} errorType={}",
                        sessionId, callRef, error.javaClass.simpleName,
                    )
                } else {
                    logger.info(
                        "voice_tutor_webrtc_sideband_closed_after_terminal sessionId={} callRef={} source={} errorType={}",
                        sessionId, callRef, terminalSource.get(), error.javaClass.simpleName,
                    )
                }
            }
        val providerFlow = Mono.usingWhen(
            Mono.just(callId),
            { providerWork },
            { cleanupProvider() },
            { _, _ -> cleanupProvider() },
            {
                signalTerminal("BRIDGE_CANCEL", cancelActiveResponse = true, reason = "CLIENT_DISCONNECTED")
                cleanupProvider()
            },
        )
            .onErrorResume { Mono.empty() }
            .doFinally { outgoing.tryEmitComplete() }

        // closeStatus is a passive, replayable signal, not a second receive()
        // subscription. Scope its observer to this bridge so a peer that never
        // sends a close frame cannot delay cleanup or leave a subscription alive.
        return Mono.using(
            {
                logger.info("voice_tutor_control_opened sessionId={} callRef={}", sessionId, callRef)
                clientSession.closeStatus().subscribe(
                    { status ->
                        clientCloseCode.set(status.code)
                        logger.info(
                            "voice_tutor_control_closed sessionId={} callRef={} closeCode={} " +
                                "localCloseInitiated={} terminalSource={} elapsedMs={}",
                            sessionId, callRef, status.code, localCloseInitiated.get(),
                            terminalSource.get(), elapsedMs(),
                        )
                    },
                    { error ->
                        logger.info(
                            "voice_tutor_control_close_status_unavailable sessionId={} callRef={} errorType={}",
                            sessionId, callRef, error.javaClass.simpleName,
                        )
                    },
                )
            },
            {
                Mono.`when`(sendToClient, clientInput, providerFlow)
                    .then(Mono.defer {
                        localCloseInitiated.set(true)
                        clientSession.close()
                    })
                    .onErrorResume {
                        localCloseInitiated.set(true)
                        clientSession.close(CloseStatus.SERVER_ERROR)
                    }
            },
            { it.dispose() },
        ).doFinally { signal ->
            logger.info(
                "voice_tutor_control_finished sessionId={} callRef={} source={} reason={} " +
                    "failed={} closeCode={} signal={} elapsedMs={}",
                sessionId, callRef, terminalSource.get(), endReason.get(), failure.get() != null,
                clientCloseCode.get() ?: "none", signal.name, elapsedMs(),
            )
        }
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
                    "pauseProtocol" to VoiceTutorRealtimeContract.PAUSE_PROTOCOL,
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
    ): Mono<Boolean> {
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return Mono.just(false)
        return when (node.path("type").asText()) {
            "conversation.item.input_audio_transcription.completed" -> appendTranscript(
                principal,
                sessionId,
                node.path("item_id").asText(),
                VoiceTutorTranscriptRole.USER,
                node.path("transcript").asText(),
                VoiceTutorTranscriptMetadata.lessonRevision(node),
            )
            "response.output_audio_transcript.done" -> appendTranscript(
                principal,
                sessionId,
                node.path("item_id").asText().ifBlank { node.path("response_id").asText() },
                VoiceTutorTranscriptRole.TUTOR,
                node.path("transcript").asText(),
                VoiceTutorTranscriptMetadata.lessonRevision(node),
            )
            else -> Mono.just(false)
        }
    }

    private fun appendTranscript(
        principal: Principal,
        sessionId: String,
        providerItemId: String,
        role: VoiceTutorTranscriptRole,
        transcript: String,
        lessonRevision: Long,
    ): Mono<Boolean> = if (transcript.isBlank()) {
        Mono.just(false)
    } else {
        mono {
            relay.appendTranscript(principal, sessionId, providerItemId, role, transcript, Instant.now(), lessonRevision)
        }
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
