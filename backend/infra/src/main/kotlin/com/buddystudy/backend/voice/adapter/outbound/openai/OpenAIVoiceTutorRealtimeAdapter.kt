package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimePort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
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
        onProviderEvent: suspend (String) -> Unit,
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
            )
            val clientEventFlux = clientEvents.asFlux()
                .doFinally { turnController.close() }
            val liveEvents = Flux.merge(clientEventFlux, turnController.providerEvents())
                .doOnNext(drainState::observeClientEvent)
            val outbound = Flux.concat(
                Mono.just(sessionUpdate(request)),
                liveEvents,
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
                    val shouldForward = turnController.observeProviderEvent(raw)
                    if (shouldForward) {
                        mono { onProviderEvent(raw) }.thenReturn(raw)
                    } else {
                        Mono.just(raw)
                    }
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

    private companion object {
        const val OPENAI_REALTIME_URL = "wss://api.openai.com/v1/realtime"
        const val MAX_PROVIDER_FRAME_BYTES = 65_536
        val PROVIDER_DRAIN_GRACE: Duration = Duration.ofSeconds(2)
    }
}

internal class VoiceTutorDuplexTurnController(
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper = JsonMapperProvider.mapper,
    private val continuousSpeechLimit: Duration,
) {
    private val controls = Sinks.many().unicast()
        .onBackpressureBuffer(Queues.get<String>(MAX_BUFFERED_CONTROLS).get())
    private var interventionTimer: Disposable? = null
    private var userSpeaking = false
    private var responseActive = false
    private var normalResponsePending = false
    private var normalInputCommitted = false
    private var suppressNextSpeechStarted = false
    private var suppressNextSpeechStoppedResponse = false
    private var closed = false

    fun providerEvents(): Flux<String> = controls.asFlux()

    @Synchronized
    fun observeProviderEvent(raw: String): Boolean {
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return true
        return when (node.path("type").asText()) {
            "input_audio_buffer.speech_started" -> {
                userSpeaking = true
                if (suppressNextSpeechStarted) {
                    suppressNextSpeechStarted = false
                    false
                } else {
                    scheduleIntervention()
                    true
                }
            }
            "input_audio_buffer.speech_stopped" -> {
                userSpeaking = false
                interventionTimer?.dispose()
                interventionTimer = null
                suppressNextSpeechStarted = false
                if (suppressNextSpeechStoppedResponse) {
                    suppressNextSpeechStoppedResponse = false
                    normalResponsePending = false
                    normalInputCommitted = false
                } else {
                    normalResponsePending = true
                    normalInputCommitted = false
                }
                true
            }
            "input_audio_buffer.committed" -> {
                if (normalResponsePending) {
                    normalInputCommitted = true
                    createNormalResponseIfReady()
                }
                true
            }
            "response.created" -> {
                responseActive = true
                true
            }
            "response.done" -> {
                responseActive = false
                createNormalResponseIfReady()
                true
            }
            "error" -> {
                val clientEventId = node.path("error").path("event_id").asText()
                if (clientEventId.startsWith(DUPLEX_EVENT_PREFIX)) {
                    responseActive = false
                    createNormalResponseIfReady()
                }
                true
            }
            else -> true
        }
    }

    @Synchronized
    internal fun fireContinuousSpeechDeadline() {
        if (closed || !userSpeaking || responseActive) return
        responseActive = true
        normalResponsePending = false
        normalInputCommitted = false
        suppressNextSpeechStarted = true
        suppressNextSpeechStoppedResponse = true
        emit(
            linkedMapOf(
                "event_id" to internalEventId("commit"),
                "type" to "input_audio_buffer.commit",
            ),
        )
        emit(
            linkedMapOf(
                "event_id" to internalEventId("continuous-response"),
                "type" to "response.create",
                "response" to linkedMapOf(
                    "instructions" to CONTINUOUS_SPEECH_INTERVENTION_INSTRUCTIONS,
                    "metadata" to linkedMapOf(
                        VoiceTutorRealtimeContract.TURN_METADATA_KEY to
                            VoiceTutorRealtimeContract.CONTINUOUS_INTERVENTION_TURN,
                    ),
                ),
            ),
        )
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        interventionTimer?.dispose()
        interventionTimer = null
        controls.tryEmitComplete()
    }

    private fun scheduleIntervention() {
        interventionTimer?.dispose()
        interventionTimer = Mono.delay(continuousSpeechLimit)
            .subscribe { fireContinuousSpeechDeadline() }
    }

    private fun createNormalResponseIfReady() {
        if (closed || !normalResponsePending || !normalInputCommitted || responseActive) return
        normalResponsePending = false
        normalInputCommitted = false
        responseActive = true
        emit(
            linkedMapOf(
                "event_id" to internalEventId("turn-response"),
                "type" to "response.create",
            ),
        )
    }

    private fun emit(event: Map<String, Any?>) {
        val result = controls.tryEmitNext(mapper.writeValueAsString(event))
        if (result.isFailure && result != Sinks.EmitResult.FAIL_CANCELLED && result != Sinks.EmitResult.FAIL_TERMINATED) {
            controls.tryEmitError(IllegalStateException("Voice Tutor provider control buffer overflowed."))
        }
    }

    private fun internalEventId(action: String): String =
        "$DUPLEX_EVENT_PREFIX$action-${UUID.randomUUID()}"

    private companion object {
        const val MAX_BUFFERED_CONTROLS = 32
        const val DUPLEX_EVENT_PREFIX = "buddystudy-internal-duplex-"
        const val CONTINUOUS_SPEECH_INTERVENTION_INSTRUCTIONS =
            "The learner has been speaking continuously. Briefly intervene only to correct an important misconception " +
                "or to refocus an overly long monologue; otherwise invite them to continue. Be concise and respectful."
    }
}

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
            "input_audio_buffer.clear" -> {
                bufferedAudio = false
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
