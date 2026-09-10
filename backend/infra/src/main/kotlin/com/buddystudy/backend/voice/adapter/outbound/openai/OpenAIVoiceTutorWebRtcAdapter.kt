package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.VoiceTutorInputAssessmentProperties
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.model.VoiceTutorWebRtcControlContext
import com.buddystudy.backend.voice.application.model.VoiceTutorSpokenFeedbackAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorSpokenQuestionAssessmentRequest
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.port.outbound.UnavailableVoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolPort
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorQuotaExhaustionPolicy
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorSpokenTerminationNotice
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcAnswer
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Creates an OpenAI WebRTC call with the standard API key kept on the server,
 * then owns response creation over the call's sideband WebSocket.
 *
 * RTP media never traverses this adapter. [clientEvents] is a BuddyStudy
 * control stream and is observed locally; no client event is forwarded to the
 * provider sideband.
 */
@Component
class OpenAIVoiceTutorWebRtcAdapter(
    private val properties: BuddyStudyProperties,
    private val inputAssessment: VoiceTutorInputAssessmentUseCase,
    private val inputAssessmentProperties: VoiceTutorInputAssessmentProperties = VoiceTutorInputAssessmentProperties(),
    private val mcpTools: VoiceTutorMcpToolPort = UnavailableVoiceTutorMcpToolPort,
) : VoiceTutorWebRtcPort {
    private val mapper = JsonMapperProvider.mapper
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.create().responseTimeout(
        Duration.ofSeconds(properties.voiceTutor.connectTimeoutSeconds.coerceIn(5, 300)),
    )
    private val webClient = WebClient.builder()
        .clientConnector(ReactorClientHttpConnector(httpClient))
        .codecs { codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_SDP_BYTES) }
        .build()
    private val sidebandClient = ReactorNettyWebSocketClient(
        httpClient,
        {
            WebsocketClientSpec.builder()
                .maxFramePayloadLength(MAX_PROVIDER_FRAME_BYTES)
        },
    )

    override suspend fun negotiate(
        request: VoiceTutorRealtimeRequest,
        offerSdp: String,
        onProviderCallCreated: suspend (callId: String) -> Unit,
    ): VoiceTutorWebRtcAnswer {
        val validatedOffer = validateWebRtcSdp(offerSdp)
        val multipart = MultipartBodyBuilder().apply {
            part("sdp", validatedOffer).contentType(APPLICATION_SDP)
            part("session", webRtcSessionConfiguration(request)).contentType(MediaType.APPLICATION_JSON)
        }.build()
        val providerCallObserved = AtomicBoolean(false)

        return retryVoiceTutorWebRtcNegotiation(
            callIdObserved = providerCallObserved::get,
            onFailure = ::logNegotiationFailure,
        ) {
            webClient.post()
                .uri(OPENAI_REALTIME_CALLS_URL)
                .headers { headers ->
                    headers.setBearerAuth(properties.openai.userContentApiKey)
                    headers.set(
                        "OpenAI-Safety-Identifier",
                        VoiceTutorSafetyIdentifier.create(request.userId, properties.openai.userContentApiKey),
                    )
                }
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart))
                .exchangeToMono { response ->
                    readNegotiationResponse(
                        response = response,
                        onProviderCallObserved = { providerCallObserved.set(true) },
                        onProviderCallCreated = onProviderCallCreated,
                    )
                }
                .awaitSingle()
        }
    }

    private fun logNegotiationFailure(failure: VoiceTutorWebRtcNegotiationFailure) {
        val provider = failure.provider
        logger.warn(
            "voice_tutor_webrtc_negotiation_attempt_failed attempt={} maxAttempts={} retry={} " +
                "retryDelayMs={} callIdObserved={} upstreamStatus={} openAiRequestId={} " +
                "providerErrorType={} providerErrorCode={} retryAfter={} errorType={}",
            failure.attempt,
            VOICE_TUTOR_WEBRTC_NEGOTIATION_MAX_ATTEMPTS,
            failure.retry,
            failure.retryDelay?.toMillis(),
            failure.callIdObserved,
            provider?.status,
            provider?.openAiRequestId,
            provider?.errorType,
            provider?.errorCode,
            provider?.retryAfter,
            safeVoiceTutorDiagnosticType(failure.error),
        )
    }

    internal fun readNegotiationResponse(
        response: ClientResponse,
        onProviderCallObserved: () -> Unit = {},
        onProviderCallCreated: suspend (callId: String) -> Unit,
    ): Mono<VoiceTutorWebRtcAnswer> {
        if (!response.statusCode().is2xxSuccessful) {
            val headers = response.headers().asHttpHeaders()
            val baseDiagnostics = VoiceTutorWebRtcProviderDiagnostics(
                status = response.statusCode().value(),
                openAiRequestId = safeVoiceTutorProviderIdentifier(headers.getFirst(OPENAI_REQUEST_ID_HEADER)),
                retryAfter = safeVoiceTutorRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)),
            )
            return response.bodyToMono(String::class.java)
                .defaultIfEmpty("")
                .onErrorReturn("")
                .flatMap { body ->
                    Mono.error(
                        VoiceTutorWebRtcProviderException(
                            "OpenAI WebRTC negotiation failed.",
                            baseDiagnostics.withStructuredError(body),
                        ),
                    )
                }
        }
        val callId = runCatching {
            callIdFromLocation(response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION))
        }.getOrElse { error ->
            return response.releaseBody().then(Mono.error(error))
        }
        onProviderCallObserved()
        return mono { onProviderCallCreated(callId) }
            .then(
                response.bodyToMono(String::class.java)
                    .switchIfEmpty(
                        Mono.error(VoiceTutorWebRtcProviderException("OpenAI returned an empty SDP answer.")),
                    )
                    .map { answerSdp ->
                        VoiceTutorWebRtcAnswer(
                            answerSdp = validateWebRtcSdp(answerSdp),
                            callId = callId,
                        )
                    },
            )
    }

    override suspend fun relaySideband(
        context: VoiceTutorWebRtcControlContext,
        clientEvents: Flow<String>,
        terminalEvents: Flow<VoiceTutorRelayTermination>,
        onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
    ) {
        val callId = validateWebRtcCallId(context.callId)
        val uri = UriComponentsBuilder.fromUriString(OPENAI_REALTIME_SIDEBAND_URL)
            .queryParam("call_id", callId).build(true).toUri()
        val headers = HttpHeaders().apply { setBearerAuth(properties.openai.userContentApiKey) }
        sidebandClient.execute(uri, headers) { session ->
            relayVoiceTutorNativeSession(session, context, clientEvents, terminalEvents, mcpTools,
                Duration.ofSeconds(properties.voiceTutor.connectTimeoutSeconds.coerceIn(5, 300)),
                Duration.ofSeconds(properties.voiceTutor.responseTimeoutSeconds.coerceIn(10, 120)), onProviderEvent)
        }.awaitSingleOrNull()
    }

    /** Retained only for protocol regression fixtures; realtime-native-v1 never enters classifier gates. */
    private suspend fun relayLegacyManualSideband(
        context: VoiceTutorWebRtcControlContext,
        clientEvents: Flow<String>,
        terminalEvents: Flow<VoiceTutorRelayTermination>,
        onProviderEvent: suspend (
            raw: String,
            persist: Boolean,
            forwardToClient: Boolean,
        ) -> Boolean,
    ) {
        val validatedCallId = validateWebRtcCallId(context.callId)
        val providerUri = UriComponentsBuilder.fromUriString(OPENAI_REALTIME_SIDEBAND_URL)
            .queryParam("call_id", validatedCallId)
            .build(true)
            .toUri()
        val headers = HttpHeaders().apply {
            setBearerAuth(properties.openai.userContentApiKey)
        }

        sidebandClient.execute(providerUri, headers) { providerSession ->
            val diagnostics = VoiceTutorSidebandDiagnostics(validatedCallId)
            val sessionHandshake = VoiceTutorWebRtcSessionHandshake(
                validatedCallId,
                Duration.ofSeconds(properties.voiceTutor.connectTimeoutSeconds.coerceIn(5, 300)),
                expectedTools = voiceTutorRealtimeFunctionTools(mcpTools.definitions()),
                transcriptionLanguage = context.session.language,
            )
            val turnController = VoiceTutorDuplexTurnController(
                mapper = mapper,
                continuousSpeechLimit = Duration.ofSeconds(
                    properties.voiceTutor.continuousSpeechInterventionSeconds.coerceIn(5, 30),
                ),
                responseTimeout = Duration.ofSeconds(
                    properties.voiceTutor.responseTimeoutSeconds.coerceIn(10, 120),
                ),
                transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
                inputCoordinator = VoiceTutorInputTurnCoordinator(limits = inputAssessmentProperties),
                toolsEnabled = mcpTools.definitions().isNotEmpty(),
                initialLessonRevision = context.initialLessonRevision,
                initialStudyMutationSnapshot = context.initialStudyMutationSnapshot,
                sessionLanguage = context.session.language,
                onProviderTurnFailure = diagnostics::observeProviderTurnFailure,
            )
            val gracefulTerminalActive = AtomicBoolean(false)
            val terminal = terminalEvents.asFlux()
                .next()
                .doOnNext { termination ->
                    diagnostics.markLocalTerminal(termination)
                    if (termination.spokenNotice == VoiceTutorSpokenTerminationNotice.MONTHLY_QUOTA_EXHAUSTED) {
                        gracefulTerminalActive.set(true)
                        turnController.requestQuotaExhaustionNotice()
                    } else {
                        turnController.close()
                    }
                }
                .cache()
            val relayRelease = terminal.flatMap { termination ->
                if (termination.spokenNotice == VoiceTutorSpokenTerminationNotice.MONTHLY_QUOTA_EXHAUSTED) {
                    quotaExhaustionRelayRelease(turnController, termination)
                } else {
                    Mono.empty()
                }.thenReturn(termination)
            }.cache()
            val observedClientEvents = clientEvents.asFlux()
                .doOnNext { raw ->
                    diagnostics.observeClientEvent(raw)
                    turnController.observeClientEvent(raw)
                }
                .ignoreElements()
                .thenMany(Flux.empty<String>())
                .takeUntilOther(relayRelease)
            val liveControls = Flux.concat(
                sessionHandshake.initialProviderEvents(),
                Flux.merge(turnController.providerEvents(), observedClientEvents),
            ).takeUntilOther(relayRelease)
            val terminalProviderEvents = relayRelease.flatMapMany { termination ->
                if (termination.cancelActiveResponse) {
                    Flux.just(responseCancelEvent("relay-terminal"))
                } else {
                    Flux.empty()
                }
            }
            val send = providerSession.send(
                Flux.concat(liveControls, terminalProviderEvents).map(providerSession::textMessage),
            )
            val providerReceive = providerSession.receive()
                .filter { it.type == WebSocketMessage.Type.TEXT }
                .map { it.payloadAsText }
                .concatMap { raw ->
                    diagnostics.observeProviderEvent(raw)
                    if (sessionHandshake.observeProviderEvent(raw)) return@concatMap Mono.empty<Void>()
                    relayVoiceTutorProviderEvent(
                        turnController,
                        raw,
                        deferPostRelayPersistence = true,
                        onProviderEvent = onProviderEvent,
                    )
                }
                .then()
            // Assessment/persistence is a separate subscriber: never await a
            // classifier inside the ordered provider receive loop. In particular
            // response.done and output_audio_buffer.stopped must stay observable.
            val inputWork = voiceTutorInputAssessmentRelay(
                controller = turnController,
                userId = context.session.userId,
                language = context.session.language,
                assessment = inputAssessment,
                onProviderEvent = onProviderEvent,
            )
            val toolWork = voiceTutorMcpToolRelay(turnController, context, mcpTools, { raw, persist, forward ->
                onProviderEvent(raw, persist, forward)
                Unit
            })
            val spokenQuestionWork = voiceTutorSpokenQuestionAssessmentRelay(
                controller = turnController,
                userId = context.session.userId,
                language = context.session.language,
                assessment = inputAssessment,
                onProviderEvent = onProviderEvent,
            )
            val spokenFeedbackWork = voiceTutorSpokenFeedbackAssessmentRelay(
                controller = turnController,
                userId = context.session.userId,
                language = context.session.language,
                assessment = inputAssessment,
                onProviderEvent = onProviderEvent,
            )
            val quotaTerminalTranscriptWork = voiceTutorQuotaTerminalTranscriptRelay(
                controller = turnController,
                onProviderEvent = onProviderEvent,
            )
            val postRelayPersistenceWork = voiceTutorPostRelayPersistenceRelay(
                controller = turnController,
                onProviderEvent = onProviderEvent,
            )
            val clientControls = turnController.clientEvents().concatMap { raw ->
                mono { onProviderEvent(raw, false, true) }.then()
            }.then()
            val serverLifecycle = turnController.serverLifecycleEvents().concatMap { raw ->
                // This is an authenticated in-process lifecycle request. It is
                // neither provider output nor a payload for the mobile client.
                mono { onProviderEvent(raw, false, false) }.then()
            }.then()
            val receive = Mono.firstWithSignal(
                holdVoiceTutorSignalForGrace(providerReceive, relayRelease, gracefulTerminalActive::get),
                holdVoiceTutorSignalForGrace(turnController.inputFailure(), relayRelease, gracefulTerminalActive::get),
                holdVoiceTutorSignalForGrace(inputWork, relayRelease, gracefulTerminalActive::get),
                holdVoiceTutorSignalForGrace(toolWork, relayRelease, gracefulTerminalActive::get),
                holdVoiceTutorSignalForGrace(spokenQuestionWork, relayRelease, gracefulTerminalActive::get),
                holdVoiceTutorSignalForGrace(spokenFeedbackWork, relayRelease, gracefulTerminalActive::get),
                holdVoiceTutorSignalForGrace(
                    quotaTerminalTranscriptWork,
                    relayRelease,
                    gracefulTerminalActive::get,
                ),
                holdVoiceTutorSignalForGrace(postRelayPersistenceWork, relayRelease, gracefulTerminalActive::get),
                holdVoiceTutorSignalForGrace(clientControls, relayRelease, gracefulTerminalActive::get),
                holdVoiceTutorSignalForGrace(serverLifecycle, relayRelease, gracefulTerminalActive::get),
            )
            val ready = sessionHandshake.awaitConfirmation().then(
                mono {
                    onProviderEvent(SIDEBAND_READY_PAYLOAD, false, true)
                    turnController.startOpeningResponse()
                }.then(),
            )
            // Subscribe both provider directions before telling the mobile peer to
            // enable its microphone, and require the provider's effective turn
            // settings before the first response. Ready success never ends the relay.
            webRtcSidebandLifecycle(receive, send, ready, diagnostics, providerSession.closeStatus())
                .then(Mono.defer { providerSession.close() })
                .doFinally { turnController.close() }
        }.awaitSingleOrNull()
    }

    override suspend fun hangup(callId: String) {
        val validatedCallId = validateWebRtcCallId(callId)
        webClient.post()
            .uri("$OPENAI_REALTIME_CALLS_URL/{callId}/hangup", validatedCallId)
            .headers { headers -> headers.setBearerAuth(properties.openai.userContentApiKey) }
            .exchangeToMono { response ->
                if (response.statusCode().is2xxSuccessful || response.statusCode().isAlreadyEndedCall()) {
                    response.releaseBody()
                } else {
                    response.releaseBody().then(
                        Mono.error(VoiceTutorWebRtcProviderException("OpenAI WebRTC hangup failed.")),
                    )
                }
            }
            .awaitSingleOrNull()
    }

    internal fun webRtcSessionConfiguration(request: VoiceTutorRealtimeRequest): String = mapper.writeValueAsString(
        linkedMapOf(
            "type" to "realtime",
            "model" to request.model,
            "instructions" to request.instructions,
            "output_modalities" to listOf("audio"),
            "tools" to voiceTutorRealtimeFunctionTools(nativeVoiceTutorDefinitions(mcpTools)),
            "tool_choice" to "auto",
            "audio" to linkedMapOf(
                "input" to linkedMapOf(
                    "transcription" to voiceTutorInputTranscription(request.language),
                    "turn_detection" to voiceTutorNativeWebRtcTurnDetection(),
                    "noise_reduction" to voiceTutorNativeWebRtcNoiseReduction(),
                ),
                "output" to linkedMapOf(
                    "voice" to request.voice,
                ),
            ),
        ),
    )

    private fun responseCancelEvent(action: String): String = mapper.writeValueAsString(
        linkedMapOf(
            "event_id" to "buddystudy-internal-${action.take(32).ifBlank { "cancel" }}-${UUID.randomUUID()}",
            "type" to "response.cancel",
        ),
    )

    private companion object {
        const val OPENAI_REALTIME_CALLS_URL = "https://api.openai.com/v1/realtime/calls"
        const val OPENAI_REALTIME_SIDEBAND_URL = "wss://api.openai.com/v1/realtime"
        const val MAX_PROVIDER_FRAME_BYTES = 65_536
        val APPLICATION_SDP: MediaType = MediaType.parseMediaType("application/sdp")
        val SIDEBAND_READY_PAYLOAD: String = JsonMapperProvider.mapper.writeValueAsString(
            mapOf("type" to VoiceTutorRealtimeContract.SIDEBAND_READY_EVENT),
        )
    }
}

/** Semantic question attestation is off the provider receive loop and fails closed before persistence. */
internal fun voiceTutorSpokenQuestionAssessmentRelay(
    controller: VoiceTutorDuplexTurnController,
    userId: Long,
    language: String,
    assessment: VoiceTutorInputAssessmentUseCase,
    onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
): Mono<Void> = controller.spokenQuestionAssessmentActions().concatMap { action ->
    mono {
        val result = try {
            Result.success(
                assessment.assessSpokenQuestion(
                    VoiceTutorSpokenQuestionAssessmentRequest(
                        userId = userId,
                        language = language,
                        focusTopic = action.focusTopic,
                        focusDifficulty = action.focusDifficulty,
                        transcript = action.transcript,
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        controller.completeSpokenQuestionAssessment(action.token, result)
    }.flatMap { boundary ->
        Flux.fromIterable(boundary.tutorTranscriptEvents)
            .concatMap { transcript ->
                mono {
                    if (!onProviderEvent(transcript, true, false)) {
                        throw VoiceTutorTutorTranscriptPersistenceException()
                    }
                }.then()
            }
            .then(
                Mono.fromRunnable {
                    if (!controller.acknowledgePostRelayBoundary(boundary.token) &&
                        controller.acceptsInputEvents()
                    ) throw VoiceTutorProviderProtocolException()
                },
            )
    }
}.then()

/** Exact answer-feedback attestation is off the provider loop and fails closed before persistence. */
internal fun voiceTutorSpokenFeedbackAssessmentRelay(
    controller: VoiceTutorDuplexTurnController,
    userId: Long,
    language: String,
    assessment: VoiceTutorInputAssessmentUseCase,
    onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
): Mono<Void> = controller.spokenFeedbackAssessmentActions().concatMap { action ->
    mono {
        val result = try {
            Result.success(
                assessment.assessSpokenFeedback(
                    VoiceTutorSpokenFeedbackAssessmentRequest(
                        userId = userId,
                        language = language,
                        focusTopic = action.focusTopic,
                        focusDifficulty = action.focusDifficulty,
                        questionTranscript = action.questionTranscript,
                        answerTranscript = action.answerTranscript,
                        feedbackTranscript = action.feedbackTranscript,
                        allowsNavigationOffer = action.allowsNavigationOffer,
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        controller.completeSpokenFeedbackAssessment(action.token, result)
    }.flatMap { boundary ->
        Flux.fromIterable(boundary.tutorTranscriptEvents)
            .concatMap { transcript ->
                mono {
                    if (!onProviderEvent(transcript, true, false)) {
                        throw VoiceTutorTutorTranscriptPersistenceException()
                    }
                }.then()
            }
            .then(
                Mono.fromRunnable {
                    if (!controller.acknowledgePostRelayBoundary(boundary.token) &&
                        controller.acceptsInputEvents()
                    ) throw VoiceTutorProviderProtocolException()
                },
            )
    }
}.then()

/**
 * Persists a tutor sentence which completed before/during the quota fence as
 * ordinary transcript only. This path deliberately has no semantic assessment
 * or acknowledgement gate, so it cannot delay the terminal notice.
 */
internal fun voiceTutorQuotaTerminalTranscriptRelay(
    controller: VoiceTutorDuplexTurnController,
    onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
): Mono<Void> = controller.quotaTerminalTutorTranscriptBatches().concatMap { batch ->
    Flux.fromIterable(batch.tutorTranscriptEvents).concatMap { transcript ->
        mono {
            if (!onProviderEvent(transcript, true, false)) {
                throw VoiceTutorTutorTranscriptPersistenceException()
            }
        }.then()
    }.then(Mono.fromRunnable {
        if (!controller.confirmQuotaTerminalTutorTranscriptBatch(batch.token) && controller.acceptsInputEvents()) {
            throw VoiceTutorProviderProtocolException()
        }
    })
}.then()

/** Serial durable worker for completed tutor boundaries, never the provider receive loop. */
internal fun voiceTutorPostRelayPersistenceRelay(
    controller: VoiceTutorDuplexTurnController,
    onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
): Mono<Void> = controller.postRelayPersistenceBoundaries().concatMap { boundary ->
    persistVoiceTutorPostRelayBoundary(controller, boundary, onProviderEvent)
}.then()

private fun persistVoiceTutorPostRelayBoundary(
    controller: VoiceTutorDuplexTurnController,
    boundary: VoiceTutorPostRelayBoundary,
    onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
): Mono<Void> = Flux.fromIterable(boundary.tutorTranscriptEvents)
    .concatMap { transcript ->
        mono {
            if (!onProviderEvent(transcript, true, false)) {
                throw VoiceTutorTutorTranscriptPersistenceException()
            }
        }.then()
    }
    .then(Mono.fromRunnable {
        if (!controller.acknowledgePostRelayBoundary(boundary.token) && controller.acceptsInputEvents()) {
            throw VoiceTutorProviderProtocolException()
        }
    })

/**
 * Ordered production boundary for a single sideband event. Observation and
 * completed-transcript batch ownership are atomic inside the controller; the
 * exact proof is relayed before staged tutor rows, and only successful storage
 * may acknowledge the boundary and release later turn/lifecycle work.
 */
internal fun relayVoiceTutorProviderEvent(
    controller: VoiceTutorDuplexTurnController,
    raw: String,
    deferPostRelayPersistence: Boolean = false,
    onProviderEvent: suspend (String, Boolean, Boolean) -> Boolean,
): Mono<Void> {
    // Take ownership eagerly while the provider concatMap is handling this
    // exact frame. A concurrent local terminal may close the controller after
    // this point, but it cannot erase the immutable transcript batch below.
    val observation = runCatching { controller.observeProviderEventWithPostRelay(raw) }
        .getOrElse { return Mono.error(it) }
    val disposition = observation.disposition
    val currentProviderEventWork = if (disposition.persist || disposition.forwardToClient) {
        mono {
            onProviderEvent(
                controller.providerEventForRelay(raw),
                disposition.persist,
                disposition.forwardToClient,
            )
        }.then()
    } else {
        Mono.empty()
    }
    val boundary = observation.postRelayBoundary ?: return currentProviderEventWork
    if (deferPostRelayPersistence) {
        return if (controller.enqueuePostRelayPersistence(boundary)) {
            currentProviderEventWork
        } else {
            Mono.error(VoiceTutorProviderProtocolException())
        }
    }
    return currentProviderEventWork.then(
        persistVoiceTutorPostRelayBoundary(controller, boundary, onProviderEvent),
    )
}

/**
 * Holds the provider relay until both the spoken terminal response has crossed
 * its device playout gate and the paid reservation boundary has arrived. A
 * missing provider/client acknowledgement is bounded after that boundary; the
 * caller can then finalize through the ordinary idempotent settlement path.
 */
internal fun quotaExhaustionRelayRelease(
    controller: VoiceTutorDuplexTurnController,
    termination: VoiceTutorRelayTermination,
    now: () -> Instant = Instant::now,
): Mono<Void> {
    require(termination.spokenNotice == VoiceTutorSpokenTerminationNotice.MONTHLY_QUOTA_EXHAUSTED)
    val hardEndsAt = requireNotNull(termination.notBefore) {
        "A monthly quota exhaustion notice requires the session hard end."
    }
    val observedAt = now()
    val untilBoundary = Duration.between(observedAt, hardEndsAt)
        .let { if (it.isNegative) Duration.ZERO else it }
    val untilRelayDeadline = Duration.between(
        observedAt,
        hardEndsAt.plusSeconds(VoiceTutorQuotaExhaustionPolicy.NOTICE_GRACE_SECONDS),
    ).let { if (it.isNegative) Duration.ZERO else it }
    val boundaryFloor = Mono.delay(untilBoundary).then()
    val noticeComplete = controller.quotaExhaustionNoticeCompletion().onErrorComplete()
    val durableTerminalWork = controller.quotaTerminalPersistenceCompletion().onErrorComplete()
    return Mono.`when`(noticeComplete, durableTerminalWork, boundaryFloor)
        .timeout(untilRelayDeadline)
        .onErrorComplete()
        .doFinally { controller.close() }
}

/**
 * Once the quota terminal owns the call, no in-flight classifier, tool, or
 * persistence branch may close the shared provider relay ahead of its notice.
 */
internal fun holdVoiceTutorSignalForGrace(
    work: Mono<Void>,
    relayRelease: Mono<*>,
    gracefulTerminalActive: () -> Boolean,
): Mono<Void> = work
    .onErrorResume { error ->
        if (gracefulTerminalActive()) relayRelease.then() else Mono.error(error)
    }
    .then(Mono.defer {
        if (gracefulTerminalActive()) relayRelease.then() else Mono.empty()
    })

internal data class VoiceTutorWebRtcProviderDiagnostics(
    val status: Int? = null,
    val openAiRequestId: String? = null,
    val errorType: String? = null,
    val errorCode: String? = null,
    val retryAfter: String? = null,
) {
    fun withStructuredError(body: String): VoiceTutorWebRtcProviderDiagnostics {
        if (body.isBlank()) return this
        val error = runCatching { JsonMapperProvider.mapper.readTree(body).path("error") }.getOrNull()
            ?.takeIf { it.isObject }
            ?: return this
        return copy(
            errorType = safeVoiceTutorProviderToken(error.path("type").takeIf { it.isTextual }?.asText()),
            errorCode = safeVoiceTutorProviderToken(error.path("code").takeIf { it.isTextual }?.asText()),
        )
    }
}

internal data class VoiceTutorWebRtcNegotiationFailure(
    val attempt: Int,
    val retry: Boolean,
    val retryDelay: Duration?,
    val callIdObserved: Boolean,
    val provider: VoiceTutorWebRtcProviderDiagnostics?,
    val error: Throwable,
)

internal suspend fun <T> retryVoiceTutorWebRtcNegotiation(
    callIdObserved: () -> Boolean,
    sleeper: suspend (Duration) -> Unit = { duration -> delay(duration.toMillis()) },
    onFailure: (VoiceTutorWebRtcNegotiationFailure) -> Unit = {},
    attemptRequest: suspend (attempt: Int) -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return attemptRequest(attempt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val observed = callIdObserved()
            val provider = (error as? VoiceTutorWebRtcProviderException)?.diagnostics
            val retry = attempt < VOICE_TUTOR_WEBRTC_NEGOTIATION_MAX_ATTEMPTS &&
                !observed &&
                isRetryableVoiceTutorWebRtcNegotiationFailure(error)
            val retryDelay = if (retry) {
                voiceTutorWebRtcNegotiationRetryDelay(attempt, provider?.retryAfter)
            } else {
                null
            }
            onFailure(
                VoiceTutorWebRtcNegotiationFailure(
                    attempt = attempt,
                    retry = retry,
                    retryDelay = retryDelay,
                    callIdObserved = observed,
                    provider = provider,
                    error = error,
                ),
            )
            if (!retry) throw error
            sleeper(requireNotNull(retryDelay))
            attempt += 1
        }
    }
}

internal fun isRetryableVoiceTutorWebRtcNegotiationFailure(error: Throwable): Boolean = when (error) {
    is VoiceTutorWebRtcProviderException -> error.diagnostics?.let { diagnostics ->
        diagnostics.status?.let(::isRetryableVoiceTutorWebRtcStatus) == true &&
            diagnostics.isExplicitTransientRateLimit() &&
            !diagnostics.isPermanentQuotaFailure()
    } == true
    else -> false
}

// Creating a Realtime call is not idempotent. A gateway 5xx can arrive after
// the provider created the paid call but before its Location header reached us,
// so retry only an explicit rate-limit rejection. Network and 5xx failures must
// be retried by a fresh user action unless OpenAI documents idempotency support.
internal fun isRetryableVoiceTutorWebRtcStatus(status: Int): Boolean = status == 429

private fun VoiceTutorWebRtcProviderDiagnostics.isPermanentQuotaFailure(): Boolean = status == 429 &&
    (errorCode.equals("credit_balance_exhausted", ignoreCase = true) ||
        errorType.equals("insufficient_quota", ignoreCase = true))

private fun VoiceTutorWebRtcProviderDiagnostics.isExplicitTransientRateLimit(): Boolean = status == 429 &&
    (errorType.equals("rate_limit_error", ignoreCase = true) ||
        errorCode.equals("rate_limit_exceeded", ignoreCase = true))

internal fun voiceTutorWebRtcNegotiationRetryDelay(
    failedAttempt: Int,
    retryAfter: String?,
    now: Instant = Instant.now(),
): Duration {
    val fallback = VOICE_TUTOR_WEBRTC_NEGOTIATION_RETRY_DELAYS[
        (failedAttempt - 1).coerceIn(0, VOICE_TUTOR_WEBRTC_NEGOTIATION_RETRY_DELAYS.lastIndex)
    ]
    val providerDelay = voiceTutorRetryAfterDelay(retryAfter, now) ?: return fallback
    return if (providerDelay > fallback) providerDelay else fallback
}

private fun voiceTutorRetryAfterDelay(retryAfter: String?, now: Instant): Duration? {
    val value = safeVoiceTutorRetryAfter(retryAfter) ?: return null
    val seconds = value.toLongOrNull()
    val requested = if (seconds != null) {
        Duration.ofSeconds(seconds)
    } else {
        val retryAt = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            .getOrNull()
            ?: return null
        Duration.between(now, retryAt).let { if (it.isNegative) Duration.ZERO else it }
    }
    return if (requested > VOICE_TUTOR_WEBRTC_NEGOTIATION_MAX_RETRY_DELAY) {
        VOICE_TUTOR_WEBRTC_NEGOTIATION_MAX_RETRY_DELAY
    } else {
        requested
    }
}

internal fun safeVoiceTutorRetryAfter(value: String?): String? {
    val candidate = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_RETRY_AFTER_CHARACTERS }
        ?: return null
    if (candidate.all(Char::isDigit) && candidate.toLongOrNull() != null) return candidate
    return runCatching {
        ZonedDateTime.parse(candidate, DateTimeFormatter.RFC_1123_DATE_TIME)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
    }.getOrNull()
}

internal fun safeVoiceTutorProviderIdentifier(value: String?): String? = value?.trim()
    ?.takeIf { candidate ->
        candidate.isNotEmpty() &&
            candidate.length <= MAX_PROVIDER_IDENTIFIER_CHARACTERS &&
            candidate.all { character -> character.isAsciiLetterOrDigit() || character == '_' || character == '-' }
    }

private fun safeVoiceTutorProviderToken(value: String?): String? = value?.trim()
    ?.takeIf { candidate ->
        candidate.isNotEmpty() &&
            candidate.length <= MAX_PROVIDER_TOKEN_CHARACTERS &&
            candidate.all { character ->
                character.isAsciiLetterOrDigit() || character == '_' || character == '-' || character == '.'
            }
    }

private fun safeVoiceTutorDiagnosticType(error: Throwable): String = error.javaClass.simpleName
    .take(MAX_PROVIDER_TOKEN_CHARACTERS)
    .filter { character ->
        character.isAsciiLetterOrDigit() || character == '_' || character == '-' || character == '.'
    }
    .ifBlank { "Throwable" }

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

internal fun validateWebRtcSdp(sdp: String): String {
    if (sdp.isBlank() || sdp.toByteArray(StandardCharsets.UTF_8).size > MAX_SDP_BYTES || '\u0000' in sdp) {
        throw VoiceTutorWebRtcSdpException()
    }
    val lines = sdp.split('\n').map { line -> line.removeSuffix("\r") }
    if (lines.size > MAX_SDP_LINES || lines.any { line -> line.length > MAX_SDP_LINE_CHARACTERS || '\r' in line }) {
        throw VoiceTutorWebRtcSdpException()
    }
    if (lines.firstOrNull() != "v=0") {
        throw VoiceTutorWebRtcSdpException()
    }
    val mediaTypes = lines.filter { line -> line.startsWith("m=") }
        .map { line -> line.substringAfter("m=").substringBefore(' ') }
    if (mediaTypes.count { it == "audio" } != 1 || mediaTypes.any { it != "audio" }) {
        throw VoiceTutorWebRtcSdpException()
    }
    if (lines.none { line -> SDP_SHA256_FINGERPRINT.matches(line) } ||
        lines.none { line -> line.startsWith("a=ice-ufrag:") && line.length > "a=ice-ufrag:".length } ||
        lines.none { line -> line.startsWith("a=ice-pwd:") && line.length > "a=ice-pwd:".length }
    ) {
        throw VoiceTutorWebRtcSdpException()
    }
    return sdp
}

internal fun callIdFromLocation(location: String?): String {
    if (location.isNullOrBlank()) throw VoiceTutorWebRtcCallIdException()
    val uri = runCatching { URI(location) }.getOrElse { throw VoiceTutorWebRtcCallIdException() }
    if (uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null) {
        throw VoiceTutorWebRtcCallIdException()
    }
    if (uri.isAbsolute) {
        if (uri.scheme != "https" || !uri.host.equals("api.openai.com", ignoreCase = true) || uri.port != -1) {
            throw VoiceTutorWebRtcCallIdException()
        }
    } else if (uri.scheme != null || uri.rawAuthority != null) {
        throw VoiceTutorWebRtcCallIdException()
    }
    val match = OPENAI_CALL_LOCATION_PATTERN.matchEntire(uri.rawPath ?: "")
        ?: throw VoiceTutorWebRtcCallIdException()
    return validateWebRtcCallId(match.groupValues[1])
}

internal fun validateWebRtcCallId(callId: String): String {
    if (!OPENAI_CALL_ID_PATTERN.matches(callId)) throw VoiceTutorWebRtcCallIdException()
    return callId
}

internal fun HttpStatusCode.isAlreadyEndedCall(): Boolean = value() in setOf(404, 409, 410)

internal class VoiceTutorWebRtcSdpException : IllegalArgumentException("Invalid WebRTC SDP.")
internal class VoiceTutorWebRtcCallIdException : IllegalArgumentException("Invalid OpenAI WebRTC call id.")
internal class VoiceTutorWebRtcProviderException(
    message: String,
    val diagnostics: VoiceTutorWebRtcProviderDiagnostics? = null,
) : RuntimeException(message)
internal class VoiceTutorTutorTranscriptPersistenceException : RuntimeException(
    "Voice Tutor tutor transcript persistence failed.",
)

private const val MAX_SDP_BYTES = 65_536
private const val MAX_SDP_LINES = 512
private const val MAX_SDP_LINE_CHARACTERS = 4_096
private const val MAX_PROVIDER_IDENTIFIER_CHARACTERS = 128
private const val MAX_PROVIDER_TOKEN_CHARACTERS = 96
private const val MAX_RETRY_AFTER_CHARACTERS = 64
private const val OPENAI_REQUEST_ID_HEADER = "x-request-id"
internal const val VOICE_TUTOR_WEBRTC_NEGOTIATION_MAX_ATTEMPTS = 3
private val VOICE_TUTOR_WEBRTC_NEGOTIATION_RETRY_DELAYS = listOf(
    Duration.ofMillis(250),
    Duration.ofMillis(750),
)
private val VOICE_TUTOR_WEBRTC_NEGOTIATION_MAX_RETRY_DELAY = Duration.ofSeconds(2)
private val SDP_SHA256_FINGERPRINT = Regex("a=fingerprint:sha-256(?: [0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){31})")
private val OPENAI_CALL_ID_PATTERN = Regex("rtc_[A-Za-z0-9_-]{1,187}")
private val OPENAI_CALL_LOCATION_PATTERN = Regex("/v1/realtime/calls/(rtc_[A-Za-z0-9_-]{1,187})")
