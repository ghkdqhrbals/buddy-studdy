package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import reactor.util.concurrent.Queues
import java.time.Duration
import java.util.ArrayDeque
import java.util.Base64
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.TimeoutException

@Component
class OpenAIVoiceTutorRealtimeAdapter(
    private val properties: BuddyStudyProperties,
) : VoiceTutorRealtimePort {
    private val mapper = JsonMapperProvider.mapper
    private val client = ReactorNettyWebSocketClient(
        HttpClient.create().responseTimeout(
            Duration.ofSeconds(properties.voiceTutor.connectTimeoutSeconds.coerceIn(5, 300)),
        ),
        {
            WebsocketClientSpec.builder()
                .maxFramePayloadLength(MAX_PROVIDER_FRAME_BYTES)
        },
    )

    override suspend fun relay(
        request: VoiceTutorRealtimeRequest,
        clientEvents: Flow<String>,
        terminalEvents: Flow<VoiceTutorRelayTermination>,
        onProviderEvent: suspend (
            raw: String,
            persist: Boolean,
            forwardToClient: Boolean,
        ) -> Unit,
    ) {
        val providerUri = UriComponentsBuilder.fromUriString(OPENAI_REALTIME_URL)
            .queryParam("model", request.model)
            .build(true)
            .toUri()
        val headers = HttpHeaders().apply {
            setBearerAuth(properties.openai.userContentApiKey)
            set("OpenAI-Beta", "realtime=v1")
            set(
                "OpenAI-Safety-Identifier",
                VoiceTutorSafetyIdentifier.create(request.userId, properties.openai.userContentApiKey),
            )
        }
        client.execute(providerUri, headers) { providerSession ->
            val drainState = VoiceTutorProviderDrainState(mapper)
            val turnController = VoiceTutorDuplexTurnController(
                mapper = mapper,
                continuousSpeechLimit = Duration.ofSeconds(
                    properties.voiceTutor.continuousSpeechInterventionSeconds.coerceIn(5, 30),
                ),
                responseTimeout = Duration.ofSeconds(
                    properties.voiceTutor.responseTimeoutSeconds.coerceIn(10, 120),
                ),
                transport = VoiceTutorRealtimeTransport.LEGACY_PCM_RELAY,
            )
            val clientEventFlux = clientEvents.asFlux()
                .handle<String> { raw, sink ->
                    if (!turnController.observeClientEvent(raw)) {
                        sink.next(raw)
                    }
                }
                .doFinally { turnController.close() }
            val terminal = terminalEvents.asFlux()
                .next()
                .doOnNext { turnController.close() }
                .cache()
            val liveEvents = Flux.merge(clientEventFlux, turnController.providerEvents())
                .takeUntilOther(terminal)
                .doOnNext(drainState::observeClientEvent)
            val terminalProviderEvents = terminal.flatMapMany { termination ->
                if (termination.cancelActiveResponse) {
                    Flux.just(responseCancelEvent("relay-terminal"))
                } else {
                    Flux.empty()
                }
            }
            val outbound = Flux.concat(
                Mono.just(sessionUpdate(request)),
                liveEvents,
                terminalProviderEvents,
                Mono.defer {
                    val commit = drainState.beginDrain()
                    if (commit == null) Mono.empty<String>() else Mono.just(commit)
                },
            )
            val send = providerSession.send(
                outbound.map(providerSession::textMessage),
            )
            val receive = providerSession.receive()
                .filter { it.type == WebSocketMessage.Type.TEXT }
                .map { it.payloadAsText }
                .concatMap { raw ->
                    val disposition = turnController.observeProviderEvent(raw)
                    mono {
                        onProviderEvent(
                            raw,
                            disposition.persist,
                            disposition.forwardToClient,
                        )
                    }.thenReturn(raw)
                }
                .doOnNext(drainState::observeProviderEvent)
                .then()
            val sendThenDrain = send
                .then(Mono.defer { drainState.awaitDrain(PROVIDER_DRAIN_GRACE) })
                .then(Mono.defer { providerSession.close() })

            Mono.firstWithSignal(
                receive,
                sendThenDrain,
            ).then()
        }.awaitSingleOrNull()
    }

    internal fun sessionUpdate(request: VoiceTutorRealtimeRequest): String = mapper.writeValueAsString(
        mapOf(
            "type" to "session.update",
            "session" to mapOf(
                "type" to "realtime",
                "model" to request.model,
                "instructions" to request.instructions,
                "output_modalities" to listOf("audio"),
                "audio" to mapOf(
                    "input" to mapOf(
                        "format" to mapOf(
                            "type" to "audio/pcm",
                            "rate" to 24_000,
                        ),
                        "transcription" to mapOf("model" to "gpt-4o-mini-transcribe"),
                        "turn_detection" to mapOf(
                            "type" to "server_vad",
                            "create_response" to false,
                            "interrupt_response" to false,
                        ),
                    ),
                    "output" to mapOf(
                        "format" to mapOf(
                            "type" to "audio/pcm",
                            "rate" to 24_000,
                        ),
                        "voice" to request.voice,
                    ),
                ),
            ),
        ),
    )

    internal fun responseCancelEvent(action: String): String = mapper.writeValueAsString(
        linkedMapOf(
            "event_id" to "buddystudy-internal-${action.take(32).ifBlank { "cancel" }}-${UUID.randomUUID()}",
            "type" to "response.cancel",
        ),
    )

    private companion object {
        const val OPENAI_REALTIME_URL = "wss://api.openai.com/v1/realtime"
        const val MAX_PROVIDER_FRAME_BYTES = 65_536
        val PROVIDER_DRAIN_GRACE: Duration = Duration.ofSeconds(2)
    }
}

internal enum class VoiceTutorRealtimeTransport {
    LEGACY_PCM_RELAY,
    WEBRTC_SIDEBAND,
}

internal class VoiceTutorDuplexTurnController(
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper = JsonMapperProvider.mapper,
    private val continuousSpeechLimit: Duration,
    private val responseTimeout: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
    private val transport: VoiceTutorRealtimeTransport = VoiceTutorRealtimeTransport.LEGACY_PCM_RELAY,
) {
    private val controls = Sinks.many().unicast()
        .onBackpressureBuffer(Queues.get<String>(MAX_BUFFERED_CONTROLS).get())
    private var interventionTimer: Disposable? = null
    private var userSpeaking = false
    private var interventionDeliveredDuringCurrentSpeech = false
    private var responseActive = false
    private var activeResponseGeneration = 0L
    private var activeResponseCreateEventId: String? = null
    private var activeResponseId: String? = null
    private var activeResponseAudioBytes = 0L
    private var earliestResponsePlaybackEndNanos: Long? = null
    private var providerResponseDone = false
    private var playbackCompleted = false
    private var providerOutputBufferStopped = false
    private var clientPlayoutDrained = false
    private var playbackTimer: Disposable? = null
    private var responseTimer: Disposable? = null
    private var openingResponseRequested = false
    private var openingResponsePending = false
    private var queuedCommittedTurn = false
    private var pendingSpeechCommitCount = 0
    private var lastClientSpeechSequence = 0L
    private var activeClientSpeechSequence: Long? = null
    private val pendingInputCommits = ArrayDeque<PendingInputCommit>()
    private val recentCommittedItemIds = LinkedHashSet<String>()
    private var inputCommitTimer: Disposable? = null
    private var interventionDeadlineElapsedWhileResponseActive = false
    @Volatile
    private var closed = false

    fun providerEvents(): Flux<String> = controls.asFlux().filter { !closed }

    @Synchronized
    fun startOpeningResponse() {
        if (closed || openingResponseRequested) return
        openingResponseRequested = true
        openingResponsePending = true
        createNormalResponseIfReady()
    }

    @Synchronized
    fun observeClientEvent(raw: String): Boolean {
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return false
        return when (node.path("type").asText()) {
            VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT -> {
                if (!closed && transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                    clientSpeechSequence(node)?.let(::observeClientSpeechStarted)
                }
                // These notifications control the server's turn state only.
                // Neither their type nor their client-supplied fields go upstream.
                true
            }
            VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT -> {
                if (!closed && transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                    clientSpeechSequence(node)?.let(::observeClientSpeechStopped)
                }
                true
            }
            VoiceTutorRealtimeContract.PLAYBACK_COMPLETED_EVENT -> {
                if (closed || transport != VoiceTutorRealtimeTransport.LEGACY_PCM_RELAY) return true
                val responseId = node.path("responseId").asText()
                if (responseActive && responseId.isNotBlank() && responseId == activeResponseId) {
                    playbackCompleted = true
                    if (providerResponseDone) {
                        advancePlaybackGate()
                    }
                }
                true
            }
            VoiceTutorRealtimeContract.PLAYOUT_DRAINED_EVENT -> {
                if (closed || transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) return true
                val responseId = node.path("responseId").asText()
                if (responseActive && responseId.isNotBlank() && responseId == activeResponseId) {
                    clientPlayoutDrained = true
                    advancePlaybackGate()
                }
                true
            }
            else -> false
        }
    }

    @Synchronized
    fun observeProviderEvent(raw: String): VoiceTutorProviderRelayDisposition {
        val node = runCatching { mapper.readTree(raw) }.getOrNull()
            ?: return VoiceTutorProviderRelayDisposition.DROP
        if (closed) {
            return terminalDisposition(node)
        }
        return when (node.path("type").asText()) {
            "session.updated" -> {
                if (transport == VoiceTutorRealtimeTransport.LEGACY_PCM_RELAY) {
                    startOpeningResponse()
                }
                VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
            }
            "response.output_audio.delta" -> if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                accepted(
                    matchesKnownActiveResponse(node.path("response_id").asText()),
                    VoiceTutorProviderRelayDisposition.PERSIST_ONLY,
                )
            } else {
                accepted(observeResponseAudio(node))
            }
            "response.output_audio.done" -> accepted(matchesActiveResponse(node.path("response_id").asText()))
            "output_audio_buffer.started" -> accepted(
                transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
                    matchesKnownActiveResponse(node.path("response_id").asText()),
                VoiceTutorProviderRelayDisposition.FORWARD_ONLY,
            )
            "output_audio_buffer.stopped" -> accepted(
                observeOutputBufferStopped(node),
                VoiceTutorProviderRelayDisposition.FORWARD_ONLY,
            )
            "output_audio_buffer.cleared" -> observeOutputBufferCleared(node)
            in TUTOR_TRANSCRIPT_EVENTS -> accepted(matchesKnownActiveResponse(node.path("response_id").asText()))
            in USER_TRANSCRIPT_EVENTS -> VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
            "input_audio_buffer.speech_started" -> {
                if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                    return VoiceTutorProviderRelayDisposition.DROP
                }
                observeSpeechStarted()
                VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
            }
            "input_audio_buffer.speech_stopped" -> {
                if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
                    return VoiceTutorProviderRelayDisposition.DROP
                }
                observeSpeechStopped()
                VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
            }
            "input_audio_buffer.committed" -> {
                if (
                    transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
                    !acknowledgeInputCommit(node)
                ) return VoiceTutorProviderRelayDisposition.DROP
                if (pendingSpeechCommitCount > 0) {
                    pendingSpeechCommitCount -= 1
                }
                queuedCommittedTurn = true
                createNormalResponseIfReady()
                if (
                    transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
                    pendingInputCommits.isEmpty() && userSpeaking &&
                    interventionDeadlineElapsedWhileResponseActive
                ) {
                    fireContinuousSpeechDeadline()
                }
                VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
            }
            "response.created" -> accepted(observeResponseCreated(node))
            "response.done" -> accepted(observeResponseDone(node))
            "error" -> VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
            else -> VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST
        }
    }

    @Synchronized
    internal fun fireContinuousSpeechDeadline() {
        interventionTimer = null
        if (
            closed || !openingResponseRequested || openingResponsePending ||
            !userSpeaking || interventionDeliveredDuringCurrentSpeech
        ) return
        if (responseActive || pendingInputCommits.isNotEmpty()) {
            interventionDeadlineElapsedWhileResponseActive = true
            return
        }
        interventionDeliveredDuringCurrentSpeech = true
        interventionDeadlineElapsedWhileResponseActive = false
        val responseEventId = internalEventId("continuous-response")
        beginResponse(responseEventId)
        emit(
            linkedMapOf(
                "event_id" to responseEventId,
                "type" to "response.create",
                "response" to linkedMapOf(
                    "instructions" to CONTINUOUS_SPEECH_INTERVENTION_INSTRUCTIONS,
                    "metadata" to linkedMapOf(
                        VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to responseEventId,
                        VoiceTutorRealtimeContract.TURN_METADATA_KEY to
                            VoiceTutorRealtimeContract.CONTINUOUS_INTERVENTION_TURN,
                    ),
                ),
            ),
        )
    }

    fun close() {
        closed = true
        synchronized(this) {
            interventionTimer?.dispose()
            interventionTimer = null
            playbackTimer?.dispose()
            playbackTimer = null
            responseTimer?.dispose()
            responseTimer = null
            inputCommitTimer?.dispose()
            inputCommitTimer = null
            pendingInputCommits.clear()
            recentCommittedItemIds.clear()
            activeClientSpeechSequence = null
            controls.tryEmitComplete()
        }
    }

    private fun clientSpeechSequence(node: com.fasterxml.jackson.databind.JsonNode): Long? {
        val sequence = node.path("sequence")
        return sequence.takeIf { it.isIntegralNumber && it.canConvertToLong() }
            ?.longValue()?.takeIf { it > 0 }
    }

    private fun observeClientSpeechStarted(sequence: Long) {
        if (sequence <= lastClientSpeechSequence || activeClientSpeechSequence != null) return
        if (pendingSpeechCommitCount >= MAX_PENDING_SPEECH_COMMITS) {
            throw VoiceTutorPendingInputCommitOverflowException()
        }
        lastClientSpeechSequence = sequence
        activeClientSpeechSequence = sequence
        observeSpeechStarted()
    }

    private fun observeClientSpeechStopped(sequence: Long) {
        if (activeClientSpeechSequence != sequence) return
        activeClientSpeechSequence = null
        // Register before emitting: an immediate provider ACK must find the
        // outstanding commit, while a later start has its own pending count.
        pendingInputCommits.addLast(PendingInputCommit(sequence, nanoTime()))
        observeSpeechStopped()
        scheduleInputCommitTimeout()
        emit(
            linkedMapOf(
                "event_id" to internalEventId("input-commit"),
                "type" to "input_audio_buffer.commit",
            ),
        )
    }

    private fun observeSpeechStarted() {
        if (!userSpeaking) {
            pendingSpeechCommitCount = (pendingSpeechCommitCount + 1)
                .coerceAtMost(MAX_PENDING_SPEECH_COMMITS)
            interventionDeliveredDuringCurrentSpeech = false
        }
        userSpeaking = true
        interventionDeadlineElapsedWhileResponseActive = false
        scheduleIntervention()
    }

    private fun observeSpeechStopped() {
        userSpeaking = false
        interventionTimer?.dispose()
        interventionTimer = null
        interventionDeliveredDuringCurrentSpeech = false
        interventionDeadlineElapsedWhileResponseActive = false
        if (pendingSpeechCommitCount == 0) createNormalResponseIfReady()
    }

    private fun acknowledgeInputCommit(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        val item = node.path("item_id")
        val itemId = item.takeIf { it.isTextual }?.textValue()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_PROVIDER_ITEM_ID_CHARACTERS }
            ?: return false
        // ACKs have provider-generated item ids, not the client's sequence.
        // Retain a bounded replay window, including unsolicited ACKs, so a
        // duplicate cannot consume a later utterance's outstanding commit.
        if (!recentCommittedItemIds.add(itemId)) return false
        if (recentCommittedItemIds.size > MAX_RECENT_COMMITTED_ITEMS) {
            val oldest = recentCommittedItemIds.iterator()
            oldest.next()
            oldest.remove()
        }
        if (pendingInputCommits.isEmpty()) return false
        pendingInputCommits.removeFirst()
        scheduleInputCommitTimeout()
        return true
    }

    private fun scheduleInputCommitTimeout() {
        inputCommitTimer?.dispose()
        inputCommitTimer = null
        val pending = pendingInputCommits.peekFirst() ?: return
        val elapsed = (nanoTime() - pending.requestedAtNanos).coerceAtLeast(0)
        val remaining = (responseTimeout.toNanos() - elapsed).coerceAtLeast(1)
        inputCommitTimer = Mono.delay(Duration.ofNanos(remaining))
            .subscribe { fireInputCommitTimeout(pending.sequence) }
    }

    @Synchronized
    internal fun fireInputCommitTimeout(sequence: Long) {
        if (closed || pendingInputCommits.peekFirst()?.sequence != sequence) return
        inputCommitTimer = null
        controls.tryEmitError(VoiceTutorProviderInputCommitTimeoutException())
    }

    private fun scheduleIntervention() {
        if (closed || !openingResponseRequested || openingResponsePending || interventionDeliveredDuringCurrentSpeech) return
        interventionTimer?.dispose()
        interventionTimer = Mono.delay(continuousSpeechLimit)
            .subscribe { fireContinuousSpeechDeadline() }
    }

    private fun createNormalResponseIfReady() {
        if (
            closed || !openingResponseRequested || userSpeaking || pendingSpeechCommitCount > 0 || responseActive ||
            (!openingResponsePending && !queuedCommittedTurn)
        ) return
        val opening = openingResponsePending
        if (opening) {
            openingResponsePending = false
        } else {
            queuedCommittedTurn = false
        }
        // Opening speech uses the same response/playout gate as an ordinary turn.
        // Keep any early learner commit queued until that entire sentence is heard.
        val responseEventId = internalEventId(if (opening) "opening-response" else "turn-response")
        beginResponse(responseEventId)
        emit(
            linkedMapOf(
                "event_id" to responseEventId,
                "type" to "response.create",
                "response" to linkedMapOf(
                    "metadata" to linkedMapOf(
                        VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to responseEventId,
                    ),
                ),
            ),
        )
    }

    private fun emit(event: Map<String, Any?>) {
        if (closed) return
        val result = controls.tryEmitNext(mapper.writeValueAsString(event))
        if (result.isFailure && result != Sinks.EmitResult.FAIL_CANCELLED && result != Sinks.EmitResult.FAIL_TERMINATED) {
            controls.tryEmitError(IllegalStateException("Voice Tutor provider control buffer overflowed."))
        }
    }

    private fun beginResponse(createEventId: String) {
        playbackTimer?.dispose()
        playbackTimer = null
        activeResponseGeneration = if (activeResponseGeneration == Long.MAX_VALUE) {
            1
        } else {
            activeResponseGeneration + 1
        }
        responseActive = true
        activeResponseCreateEventId = createEventId
        activeResponseId = null
        activeResponseAudioBytes = 0
        earliestResponsePlaybackEndNanos = null
        providerResponseDone = false
        playbackCompleted = false
        providerOutputBufferStopped = false
        clientPlayoutDrained = false
        responseTimer?.dispose()
        val responseGeneration = activeResponseGeneration
        responseTimer = Mono.delay(responseTimeout)
            .subscribe { fireResponseTimeout(responseGeneration, createEventId) }
    }

    private fun observeResponseCreated(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        if (!responseActive) return false
        if (!matchesActiveResponseToken(node.path("response"))) return false
        val responseId = node.path("response").path("id").asText()
        if (!matchesActiveResponse(responseId)) return false
        if (activeResponseId == null) {
            activeResponseId = responseId
        }
        return true
    }

    private fun observeResponseAudio(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        if (!responseActive || activeResponseId == null) return false
        val responseId = node.path("response_id").asText()
        if (!matchesActiveResponse(responseId)) return false
        if (activeResponseId == null) {
            activeResponseId = responseId
        }
        val bytes = runCatching { Base64.getDecoder().decode(node.path("delta").asText()).size.toLong() }
            .getOrDefault(0)
        if (bytes > 0) {
            val receivedAt = nanoTime()
            val playbackStart = maxOf(earliestResponsePlaybackEndNanos ?: receivedAt, receivedAt)
            earliestResponsePlaybackEndNanos = playbackStart + audioDurationNanos(bytes)
        }
        activeResponseAudioBytes = (activeResponseAudioBytes + bytes).coerceAtMost(MAX_RESPONSE_AUDIO_BYTES)
        return true
    }

    private fun observeResponseDone(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        if (!responseActive) return false
        val response = node.path("response")
        if (
            transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND &&
            response.path("status").asText() != "completed"
        ) {
            throw VoiceTutorProviderIncompleteResponseException()
        }
        if (response.path("status").asText() !in COMPLETING_RESPONSE_STATUSES) return true
        if (!matchesActiveResponseToken(response)) return false
        val responseId = response.path("id").asText()
        if (!matchesActiveResponse(responseId)) return false
        if (activeResponseId == null) {
            activeResponseId = responseId
        }
        providerResponseDone = true
        responseTimer?.dispose()
        responseTimer = null
        advancePlaybackGate()
        return true
    }

    private fun observeOutputBufferStopped(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        if (transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) return false
        val responseId = node.path("response_id").asText()
        if (!matchesKnownActiveResponse(responseId)) return false
        providerOutputBufferStopped = true
        advancePlaybackGate()
        return true
    }

    private fun observeOutputBufferCleared(
        node: com.fasterxml.jackson.databind.JsonNode,
    ): VoiceTutorProviderRelayDisposition {
        if (transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
            return VoiceTutorProviderRelayDisposition.DROP
        }
        // A cleared WebRTC buffer means a tutor sentence was cut short. It is never
        // a valid turn completion, including when a stale or forged response id is used.
        node.path("response_id").asText()
        throw VoiceTutorProviderOutputBufferClearedException()
    }

    private fun matchesActiveResponse(responseId: String): Boolean =
        responseActive && responseId.isNotBlank() && (activeResponseId == null || responseId == activeResponseId)

    private fun matchesKnownActiveResponse(responseId: String): Boolean =
        responseActive && activeResponseId != null && responseId == activeResponseId

    private fun matchesActiveResponseToken(response: com.fasterxml.jackson.databind.JsonNode): Boolean =
        response.path("metadata").path(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY).asText() ==
            activeResponseCreateEventId

    private fun advancePlaybackGate() {
        if (!responseActive || !providerResponseDone) return
        if (transport == VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND) {
            if (providerOutputBufferStopped && clientPlayoutDrained) {
                finishActiveResponse()
            } else if (playbackTimer == null) {
                val responseGeneration = activeResponseGeneration
                val responseId = activeResponseId
                playbackTimer = Mono.delay(responseTimeout)
                    .subscribe { fireWebRtcPlayoutTimeout(responseGeneration, responseId) }
            }
            return
        }
        if (activeResponseAudioBytes == 0L) {
            finishActiveResponse()
            return
        }
        if (playbackCompleted) {
            val remainingNanos = earliestPlaybackCompletionNanos() - nanoTime()
            if (remainingNanos <= 0) {
                finishActiveResponse()
            } else {
                schedulePlaybackTimer(remainingNanos, requiresAcknowledgement = true)
            }
        } else {
            val fallbackNanos = (responseAudioDurationNanos() + PLAYBACK_ACK_GRACE_NANOS)
                .coerceAtLeast(PLAYBACK_ACK_MIN_NANOS)
            schedulePlaybackTimer(fallbackNanos, requiresAcknowledgement = false)
        }
    }

    private fun earliestPlaybackCompletionNanos(): Long {
        return earliestResponsePlaybackEndNanos ?: nanoTime()
    }

    private fun responseAudioDurationNanos(): Long =
        audioDurationNanos(activeResponseAudioBytes)

    private fun audioDurationNanos(audioBytes: Long): Long =
        ((audioBytes * NANOS_PER_SECOND) + PCM_BYTES_PER_SECOND - 1) / PCM_BYTES_PER_SECOND

    private fun schedulePlaybackTimer(delayNanos: Long, requiresAcknowledgement: Boolean) {
        val responseGeneration = activeResponseGeneration
        val responseId = activeResponseId
        playbackTimer?.dispose()
        playbackTimer = Mono.delay(Duration.ofNanos(delayNanos.coerceAtLeast(1)))
            .subscribe {
                firePlaybackTimer(responseGeneration, responseId, requiresAcknowledgement)
            }
    }

    @Synchronized
    internal fun firePlaybackTimeout(responseId: String?) {
        firePlaybackTimer(activeResponseGeneration, responseId, requiresAcknowledgement = false)
    }

    @Synchronized
    internal fun firePlaybackFloor(responseId: String?) {
        firePlaybackTimer(activeResponseGeneration, responseId, requiresAcknowledgement = true)
    }

    @Synchronized
    internal fun fireResponseTimeout(createEventId: String?) {
        fireResponseTimeout(activeResponseGeneration, createEventId)
    }

    @Synchronized
    internal fun fireResponseTimeout(responseGeneration: Long, createEventId: String?) {
        if (
            closed ||
            !responseActive ||
            providerResponseDone ||
            responseGeneration != activeResponseGeneration ||
            createEventId != activeResponseCreateEventId
        ) {
            return
        }
        responseTimer = null
        emit(
            linkedMapOf(
                "event_id" to "buddystudy-internal-response-timeout-${UUID.randomUUID()}",
                "type" to "response.cancel",
            ),
        )
        controls.tryEmitError(VoiceTutorProviderResponseTimeoutException())
    }

    @Synchronized
    internal fun fireWebRtcPlayoutTimeout(responseGeneration: Long, responseId: String?) {
        if (
            closed ||
            transport != VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND ||
            !responseActive ||
            !providerResponseDone ||
            (providerOutputBufferStopped && clientPlayoutDrained) ||
            responseGeneration != activeResponseGeneration ||
            responseId != activeResponseId
        ) {
            return
        }
        playbackTimer = null
        controls.tryEmitError(VoiceTutorProviderPlayoutTimeoutException())
    }

    @Synchronized
    private fun firePlaybackTimer(
        responseGeneration: Long,
        responseId: String?,
        requiresAcknowledgement: Boolean,
    ) {
        if (
            !closed &&
            responseActive &&
            providerResponseDone &&
            responseGeneration == activeResponseGeneration &&
            responseId == activeResponseId &&
            (!requiresAcknowledgement || playbackCompleted)
        ) {
            if (nanoTime() >= earliestPlaybackCompletionNanos()) {
                finishActiveResponse()
            } else {
                advancePlaybackGate()
            }
        }
    }

    private fun finishActiveResponse() {
        if (!responseActive) return
        playbackTimer?.dispose()
        playbackTimer = null
        responseTimer?.dispose()
        responseTimer = null
        responseActive = false
        activeResponseCreateEventId = null
        activeResponseId = null
        activeResponseAudioBytes = 0
        earliestResponsePlaybackEndNanos = null
        providerResponseDone = false
        playbackCompleted = false
        providerOutputBufferStopped = false
        clientPlayoutDrained = false
        if (closed) return
        if (userSpeaking) {
            if (interventionDeadlineElapsedWhileResponseActive) {
                fireContinuousSpeechDeadline()
            } else if (interventionTimer == null) {
                scheduleIntervention()
            }
        } else {
            createNormalResponseIfReady()
        }
    }

    private fun internalEventId(action: String): String =
        "$DUPLEX_EVENT_PREFIX$action-${UUID.randomUUID()}"

    private fun accepted(
        accepted: Boolean,
        disposition: VoiceTutorProviderRelayDisposition = VoiceTutorProviderRelayDisposition.FORWARD_AND_PERSIST,
    ): VoiceTutorProviderRelayDisposition =
        if (accepted) {
            disposition
        } else {
            VoiceTutorProviderRelayDisposition.DROP
        }

    private fun terminalDisposition(
        node: com.fasterxml.jackson.databind.JsonNode,
    ): VoiceTutorProviderRelayDisposition = when (node.path("type").asText()) {
        in USER_TRANSCRIPT_EVENTS -> VoiceTutorProviderRelayDisposition.PERSIST_ONLY
        in TUTOR_TRANSCRIPT_EVENTS -> if (matchesKnownActiveResponse(node.path("response_id").asText())) {
            VoiceTutorProviderRelayDisposition.PERSIST_ONLY
        } else {
            VoiceTutorProviderRelayDisposition.DROP
        }
        else -> VoiceTutorProviderRelayDisposition.DROP
    }

    private data class PendingInputCommit(val sequence: Long, val requestedAtNanos: Long)

    private companion object {
        const val MAX_BUFFERED_CONTROLS = 32
        const val MAX_PENDING_SPEECH_COMMITS = 32
        const val MAX_RECENT_COMMITTED_ITEMS = 64
        const val MAX_PROVIDER_ITEM_ID_CHARACTERS = 191
        const val MAX_RESPONSE_AUDIO_BYTES = 172_800_000L
        const val PCM_BYTES_PER_SECOND = 48_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val PLAYBACK_ACK_GRACE_NANOS = 5_000_000_000L
        const val PLAYBACK_ACK_MIN_NANOS = 5_000_000_000L
        const val DUPLEX_EVENT_PREFIX = "buddystudy-internal-duplex-"
        val TUTOR_TRANSCRIPT_EVENTS = setOf(
            "response.output_audio_transcript.delta",
            "response.output_audio_transcript.done",
        )
        val USER_TRANSCRIPT_EVENTS = setOf(
            "conversation.item.input_audio_transcription.delta",
            "conversation.item.input_audio_transcription.completed",
        )
        val COMPLETING_RESPONSE_STATUSES = setOf("completed", "cancelled", "incomplete")
        const val CONTINUOUS_SPEECH_INTERVENTION_INSTRUCTIONS =
            "The learner has been speaking continuously. Use exactly one short, complete, respectful sentence " +
                "asking them to wrap up their current point, then let them continue."
    }
}

internal data class VoiceTutorProviderRelayDisposition(
    val persist: Boolean,
    val forwardToClient: Boolean,
) {
    companion object {
        val DROP = VoiceTutorProviderRelayDisposition(persist = false, forwardToClient = false)
        val PERSIST_ONLY = VoiceTutorProviderRelayDisposition(persist = true, forwardToClient = false)
        val FORWARD_ONLY = VoiceTutorProviderRelayDisposition(persist = false, forwardToClient = true)
        val FORWARD_AND_PERSIST = VoiceTutorProviderRelayDisposition(persist = true, forwardToClient = true)
    }
}

internal class VoiceTutorProviderResponseTimeoutException : RuntimeException(
    "Voice Tutor provider response timed out.",
)

internal class VoiceTutorProviderPlayoutTimeoutException : RuntimeException(
    "Voice Tutor provider playout completion timed out.",
)

internal class VoiceTutorProviderInputCommitTimeoutException : RuntimeException(
    "Voice Tutor provider input commit acknowledgement timed out.",
)

internal class VoiceTutorPendingInputCommitOverflowException : RuntimeException(
    "Voice Tutor pending input commit limit exceeded.",
)

internal class VoiceTutorProviderOutputBufferClearedException : RuntimeException(
    "Voice Tutor provider cleared an active output buffer.",
)

internal class VoiceTutorProviderIncompleteResponseException : RuntimeException(
    "Voice Tutor provider returned an incomplete realtime response.",
)

internal class VoiceTutorProviderDrainState(
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper = JsonMapperProvider.mapper,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var bufferedAudio = false
    private var unidentifiedTranscriptionPending = false
    private val pendingTranscriptionItems = linkedSetOf<String>()
    private var unidentifiedResponseActive = false
    private val activeResponseIds = linkedSetOf<String>()
    private var drainStarted = false
    private var lastRelevantEventNanos = nanoTime()

    @Synchronized
    fun observeClientEvent(raw: String) {
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return
        when (node.path("type").asText()) {
            "input_audio_buffer.append" -> {
                bufferedAudio = true
                touch()
            }
            "input_audio_buffer.commit" -> {
                bufferedAudio = false
                unidentifiedTranscriptionPending = true
                touch()
            }
            "response.create" -> {
                unidentifiedResponseActive = true
                touch()
            }
        }
    }

    @Synchronized
    fun observeProviderEvent(raw: String) {
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return
        when (node.path("type").asText()) {
            "input_audio_buffer.speech_stopped", "input_audio_buffer.committed" -> {
                bufferedAudio = false
                unidentifiedTranscriptionPending = false
                node.path("item_id").asText().takeIf(String::isNotBlank)?.let(pendingTranscriptionItems::add)
                touch()
            }
            "conversation.item.input_audio_transcription.completed",
            "conversation.item.input_audio_transcription.failed",
            -> {
                unidentifiedTranscriptionPending = false
                node.path("item_id").asText().takeIf(String::isNotBlank)?.let(pendingTranscriptionItems::remove)
                touch()
            }
            "response.created" -> {
                val responseId = node.path("response").path("id").asText()
                unidentifiedResponseActive = false
                if (responseId.isBlank()) {
                    unidentifiedResponseActive = true
                } else {
                    activeResponseIds.add(responseId)
                }
                touch()
            }
            "response.done" -> {
                val responseId = node.path("response").path("id").asText()
                unidentifiedResponseActive = false
                if (responseId.isBlank()) {
                    activeResponseIds.clear()
                } else {
                    activeResponseIds.remove(responseId)
                }
                touch()
            }
        }
    }

    @Synchronized
    fun beginDrain(): String? {
        if (drainStarted) return null
        drainStarted = true
        touch()
        if (!bufferedAudio) return null
        bufferedAudio = false
        unidentifiedTranscriptionPending = true
        return mapper.writeValueAsString(
            linkedMapOf(
                "event_id" to "$INTERNAL_CONTROL_EVENT_PREFIX-drain-${UUID.randomUUID()}",
                "type" to "input_audio_buffer.commit",
            ),
        )
    }

    fun awaitDrain(maximum: Duration): Mono<Void> = Flux.interval(Duration.ZERO, DRAIN_POLL_INTERVAL)
        .filter { isSettled() }
        .next()
        .then()
        .timeout(maximum)
        .onErrorResume(TimeoutException::class.java) { Mono.empty() }

    @Synchronized
    internal fun isSettled(): Boolean = drainStarted &&
        !bufferedAudio &&
        !unidentifiedTranscriptionPending &&
        pendingTranscriptionItems.isEmpty() &&
        !unidentifiedResponseActive &&
        activeResponseIds.isEmpty() &&
        nanoTime() - lastRelevantEventNanos >= DRAIN_QUIET_NANOS

    private fun touch() {
        lastRelevantEventNanos = nanoTime()
    }

    private companion object {
        const val INTERNAL_CONTROL_EVENT_PREFIX = "buddystudy-internal"
        const val DRAIN_QUIET_NANOS = 150_000_000L
        val DRAIN_POLL_INTERVAL: Duration = Duration.ofMillis(25)
    }
}
