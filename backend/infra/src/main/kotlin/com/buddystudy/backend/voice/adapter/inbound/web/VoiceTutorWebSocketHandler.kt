package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorCreateSessionResponse
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorRelayUseCase
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorUseCase
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.util.concurrent.Queues
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Component
class VoiceTutorWebSocketHandler(
    private val relay: VoiceTutorRelayUseCase,
    private val voiceTutor: VoiceTutorUseCase,
) : WebSocketHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapperProvider.mapper
    private val eventPolicy = VoiceTutorRealtimeEventPolicy(mapper)

    override fun getSubProtocols(): List<String> = listOf(VoiceTutorCreateSessionResponse.WEBSOCKET_PROTOCOL)

    override fun handle(session: WebSocketSession): Mono<Void> {
        if (!voiceTutorSubProtocolNegotiated(session.handshakeInfo.subProtocol)) {
            return session.close(CloseStatus.PROTOCOL_ERROR)
        }
        return session.handshakeInfo.principal
            .cast(Authentication::class.java)
            .switchIfEmpty(Mono.error(IllegalStateException("Authenticated Voice Tutor principal is missing.")))
            .flatMap { authentication ->
                val principal = authentication.principal as? Principal
                    ?: return@flatMap Mono.error(IllegalStateException("Voice Tutor principal is invalid."))
                val sessionId = session.handshakeInfo.uri.path.substringBeforeLast("/stream").substringAfterLast('/')
                mono { relay.connect(principal, sessionId) }
                    .flatMap { context -> bridge(session, principal, context) }
            }
    }

    private fun bridge(
        clientSession: WebSocketSession,
        principal: Principal,
        context: com.buddystudy.backend.voice.application.model.VoiceTutorRelayContext,
    ): Mono<Void> {
        val sessionId = context.session.id
        val endReason = AtomicReference("PROVIDER_CLOSED")
        val failure = AtomicReference<Throwable?>(null)
        val userEnded = AtomicBoolean(false)
        val trafficGuard = VoiceTutorClientTrafficGuard(eventPolicy, context.session.maxSessionSeconds)
        val audioAccounting = VoiceTutorAcceptedAudioAccounting { bytes ->
            relay.recordAcceptedAudioBytes(principal, sessionId, bytes)
        }
        val outgoing = Sinks.many().unicast()
            .onBackpressureBuffer(Queues.get<String>(MAX_OUTGOING_EVENTS).get())
        val terminalSignal = Sinks.one<VoiceTutorRelayTermination>()
        val relayTerminated = AtomicBoolean(false)
        val terminalGate = Any()

        fun signalTerminal() {
            synchronized(terminalGate) {
                if (relayTerminated.compareAndSet(false, true)) {
                    terminalSignal.tryEmitValue(VoiceTutorRelayTermination(cancelActiveResponse = true))
                }
            }
        }

        fun emitProviderPayload(value: String) {
            synchronized(terminalGate) {
                if (!relayTerminated.get()) {
                    emitRequired(outgoing, value)
                }
            }
        }

        val sendToClient = clientSession.send(
            outgoing.asFlux().map(clientSession::textMessage),
        )
        val untilDeadline = Duration.between(Instant.now(), context.session.hardEndsAt)
            .coerceAtLeast(Duration.ZERO)
        val deadline = Mono.delay(untilDeadline)
            .map {
                endReason.set("TIME_LIMIT")
                signalTerminal()
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
            .filter {
                !it.authorized || it.state != com.buddystudy.voice.domain.VoiceTutorSessionStatus.ACTIVE
            }
            .next()
            .map { control ->
                val reason = when {
                    !control.authorized -> "AUTH_REVOKED"
                    control.state == com.buddystudy.voice.domain.VoiceTutorSessionStatus.ENDING -> "USER_ENDED"
                    else -> "SERVER_FINALIZED"
                }
                endReason.set(reason)
                signalTerminal()
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
        val clientInputDone = Sinks.one<Boolean>()
        val routedClientEvents = clientSession.receive()
            .map(::voiceTutorClientTextPayload)
            .takeUntil { raw ->
                val end = eventPolicy.eventType(raw) == VoiceTutorRealtimeEventPolicy.CLIENT_END_EVENT
                if (end) {
                    userEnded.set(true)
                    endReason.set("USER_ENDED")
                    signalTerminal()
                }
                end
            }
            .concatMap { raw ->
                val type = eventPolicy.eventType(raw)
                val decision = trafficGuard.inspect(raw)
                when (type) {
                    VoiceTutorRealtimeEventPolicy.CLIENT_HEARTBEAT_EVENT -> {
                        if (!decision.acceptedLocalEvent) {
                            Mono.empty()
                        } else {
                            audioAccounting.flush()
                                .doOnSuccess {
                                    emit(
                                        outgoing,
                                        synthetic(
                                            "buddystudy.voice.heartbeat.ack",
                                            sessionId,
                                            mapOf("state" to com.buddystudy.voice.domain.VoiceTutorSessionStatus.ACTIVE.name),
                                        ),
                                    )
                                }
                                .then(Mono.empty<String>())
                        }
                    }
                    VoiceTutorRealtimeEventPolicy.CLIENT_END_EVENT ->
                        audioAccounting.flush().then(Mono.empty<String>())
                    VoiceTutorRealtimeContract.PLAYBACK_COMPLETED_EVENT -> Mono.just(raw)
                    else -> if (!decision.forward) {
                        Mono.empty()
                    } else if (decision.acceptedAudioBytes > 0) {
                        audioAccounting.accept(decision.acceptedAudioBytes)
                        if (audioAccounting.shouldFlush()) {
                            audioAccounting.flush().thenReturn(raw)
                        } else {
                            Mono.just(raw)
                        }
                    } else {
                        Mono.just(raw)
                    }
                }
            }
            .takeUntilOther(serverStop)
            .doOnComplete {
                if (!userEnded.get() && endReason.get() == "PROVIDER_CLOSED") {
                    endReason.compareAndSet("PROVIDER_CLOSED", "CLIENT_DISCONNECTED")
                }
                signalTerminal()
            }
            .onErrorResume { error ->
                audioAccounting.flush().then(Mono.error(error))
            }
            .doFinally { clientInputDone.tryEmitValue(true) }

        val periodicAccounting = audioAccounting.periodicUntil(clientInputDone.asMono())
            .thenMany(Flux.empty<String>())
        val clientEvents = Flux.merge(routedClientEvents, periodicAccounting)
            .concatWith(audioAccounting.flush().then(Mono.empty<String>()))
            .onErrorResume { error ->
                audioAccounting.flush().then(Mono.error(error))
            }

        val ready = mono { voiceTutor.status(principal) }.doOnNext { status ->
                emit(
                    outgoing,
                    synthetic(
                        "buddystudy.voice.session.ready",
                        sessionId,
                        mapOf(
                            "hardEndsAt" to context.session.hardEndsAt,
                            "quotaRemainingSeconds" to status.quota.remainingSeconds,
                        ),
                    ),
                )
                emit(
                    outgoing,
                    synthetic(
                        "buddystudy.voice.quota.updated",
                        sessionId,
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

        val providerRelay = mono {
            relay.relayProvider(
                principal = principal,
                context = context,
                clientEvents = clientEvents.asFlow(),
                terminalEvents = terminalSignal.asMono().asFlow(),
            ) { raw, persist, forwardToClient ->
                val decision = eventPolicy.providerDecision(raw, sessionId, Instant.now())
                if (decision.terminate) {
                    decision.payload?.let(::emitProviderPayload)
                    signalTerminal()
                    throw VoiceTutorProviderReportedException()
                }
                if (persist) {
                    inspectProviderEvent(principal, sessionId, raw).awaitSingleOrNull()
                }
                if (forwardToClient) {
                    decision.payload?.let(::emitProviderPayload)
                }
            }
        }.then(audioAccounting.flush())
            .onErrorResume { error ->
                audioAccounting.flush().then(Mono.error(error))
            }

        val providerFlow = ready.then(providerRelay).doOnError { error ->
            failure.set(error)
            endReason.set(if (error is VoiceTutorClientProtocolException) "CLIENT_PROTOCOL_ERROR" else "PROVIDER_ERROR")
            logger.warn("voice_tutor_relay_failed sessionId={} errorType={}", sessionId, error.javaClass.simpleName)
        }.onErrorResume { Mono.empty() }
            .then(
                mono {
                    relay.finish(
                        principal = principal,
                        sessionId = sessionId,
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
                                ) {
                                    3000
                                } else {
                                    0
                                },
                            ),
                        ),
                    )
                    if (detail.resultStatus == VoiceTutorResultStatus.COMPLETED) {
                        emit(
                            outgoing,
                            synthetic(
                                "buddystudy.voice.result.ready",
                                sessionId,
                                mapOf("resultStatus" to "COMPLETED"),
                            ),
                        )
                    }
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
                },
            ).doFinally { outgoing.tryEmitComplete() }

        return Mono.`when`(sendToClient, providerFlow)
            .then(clientSession.close())
            .onErrorResume { clientSession.close() }
    }

    private fun inspectProviderEvent(principal: Principal, sessionId: String, raw: String): Mono<Void> {
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return Mono.empty()
        return when (node.path("type").asText()) {
            "session.created" -> {
                val providerSessionId = node.path("session").path("id").asText()
                if (providerSessionId.isBlank()) Mono.empty()
                else mono { relay.attachProviderSession(principal, sessionId, providerSessionId) }.then()
            }
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
            sink.tryEmitError(IllegalStateException("Voice Tutor client is not consuming audio fast enough."))
        }
    }

    private fun emitRequired(sink: Sinks.Many<String>, value: String) {
        val result = sink.tryEmitNext(value)
        if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
            sink.tryEmitError(IllegalStateException("Voice Tutor client is not consuming audio fast enough."))
            throw IllegalStateException("Voice Tutor client relay buffer overflowed.")
        }
        if (result.isFailure && result != Sinks.EmitResult.FAIL_CANCELLED && result != Sinks.EmitResult.FAIL_TERMINATED) {
            throw IllegalStateException("Voice Tutor client relay rejected an event: $result")
        }
    }

    private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (isNegative) minimum else this

    private companion object {
        const val MAX_OUTGOING_EVENTS = 128
    }
}

internal fun voiceTutorSubProtocolNegotiated(actual: String?): Boolean =
    actual == VoiceTutorCreateSessionResponse.WEBSOCKET_PROTOCOL

internal fun voiceTutorClientTextPayload(message: WebSocketMessage): String {
    if (message.type != WebSocketMessage.Type.TEXT) {
        throw VoiceTutorClientProtocolException("Voice Tutor only accepts text WebSocket frames.")
    }
    return message.payloadAsText
}

internal suspend fun pollVoiceTutorRelayControl(
    authorized: suspend () -> Boolean,
    heartbeat: suspend () -> com.buddystudy.voice.domain.VoiceTutorSessionStatus,
): VoiceTutorRelayControlSnapshot = if (authorized()) {
    VoiceTutorRelayControlSnapshot(authorized = true, state = heartbeat())
} else {
    VoiceTutorRelayControlSnapshot(authorized = false, state = null)
}

internal data class VoiceTutorRelayControlSnapshot(
    val authorized: Boolean,
    val state: com.buddystudy.voice.domain.VoiceTutorSessionStatus?,
)

internal class VoiceTutorAcceptedAudioAccounting(
    private val record: suspend (Long) -> Unit,
) {
    private var pendingBytes = 0L

    @Synchronized
    fun accept(bytes: Long) {
        if (bytes > 0) pendingBytes += bytes
    }

    @Synchronized
    fun shouldFlush(): Boolean = pendingBytes >= FLUSH_THRESHOLD_BYTES

    fun flush(): Mono<Void> = Mono.defer {
        val bytes = takeBatch()
        if (bytes <= 0) {
            Mono.empty()
        } else {
            mono { record(bytes) }
                .doOnError { restore(bytes) }
                .then(Mono.defer { flush() })
        }
    }

    fun periodicUntil(stop: Mono<*>): Flux<Void> = Flux.interval(FLUSH_INTERVAL)
        .takeUntilOther(stop)
        .concatMap { flush().then(Mono.empty<Void>()) }

    @Synchronized
    internal fun pendingBytes(): Long = pendingBytes

    @Synchronized
    private fun takeBatch(): Long {
        val batch = pendingBytes.coerceAtMost(MAX_BATCH_BYTES)
        pendingBytes -= batch
        return batch
    }

    @Synchronized
    private fun restore(bytes: Long) {
        pendingBytes += bytes
    }

    private companion object {
        const val FLUSH_THRESHOLD_BYTES = 48_000L
        const val MAX_BATCH_BYTES = 480_000L
        val FLUSH_INTERVAL: Duration = Duration.ofSeconds(1)
    }
}
