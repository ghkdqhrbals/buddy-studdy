package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRealtimeRequest
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorRelayTermination
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcAnswer
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorWebRtcPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
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
import java.util.UUID

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
) : VoiceTutorWebRtcPort {
    private val mapper = JsonMapperProvider.mapper
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

        return webClient.post()
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
            .exchangeToMono { response -> readNegotiationResponse(response, onProviderCallCreated) }
            .awaitSingle()
    }

    internal fun readNegotiationResponse(
        response: ClientResponse,
        onProviderCallCreated: suspend (callId: String) -> Unit,
    ): Mono<VoiceTutorWebRtcAnswer> {
        if (!response.statusCode().is2xxSuccessful) {
            return response.releaseBody().then(
                Mono.error(VoiceTutorWebRtcProviderException("OpenAI WebRTC negotiation failed.")),
            )
        }
        val callId = runCatching {
            callIdFromLocation(response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION))
        }.getOrElse { error ->
            return response.releaseBody().then(Mono.error(error))
        }
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
        callId: String,
        clientEvents: Flow<String>,
        terminalEvents: Flow<VoiceTutorRelayTermination>,
        onProviderEvent: suspend (
            raw: String,
            persist: Boolean,
            forwardToClient: Boolean,
        ) -> Unit,
    ) {
        val validatedCallId = validateWebRtcCallId(callId)
        val providerUri = UriComponentsBuilder.fromUriString(OPENAI_REALTIME_SIDEBAND_URL)
            .queryParam("call_id", validatedCallId)
            .build(true)
            .toUri()
        val headers = HttpHeaders().apply {
            setBearerAuth(properties.openai.userContentApiKey)
            set("OpenAI-Beta", "realtime=v1")
        }

        sidebandClient.execute(providerUri, headers) { providerSession ->
            val diagnostics = VoiceTutorSidebandDiagnostics(validatedCallId)
            val turnController = VoiceTutorDuplexTurnController(
                mapper = mapper,
                continuousSpeechLimit = Duration.ofSeconds(
                    properties.voiceTutor.continuousSpeechInterventionSeconds.coerceIn(5, 30),
                ),
                responseTimeout = Duration.ofSeconds(
                    properties.voiceTutor.responseTimeoutSeconds.coerceIn(10, 120),
                ),
                transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            )
            val terminal = terminalEvents.asFlux()
                .next()
                .doOnNext { termination ->
                    diagnostics.markLocalTerminal(termination)
                    turnController.close()
                }
                .cache()
            val observedClientEvents = clientEvents.asFlux()
                .doOnNext { raw ->
                    diagnostics.observeClientEvent(raw)
                    turnController.observeClientEvent(raw)
                }
                .ignoreElements()
                .thenMany(Flux.empty<String>())
                .doFinally { turnController.close() }
            val liveControls = Flux.merge(turnController.providerEvents(), observedClientEvents)
                .takeUntilOther(terminal)
            val terminalProviderEvents = terminal.flatMapMany { termination ->
                if (termination.cancelActiveResponse) {
                    Flux.just(responseCancelEvent("relay-terminal"))
                } else {
                    Flux.empty()
                }
            }
            val send = providerSession.send(
                Flux.concat(liveControls, terminalProviderEvents).map(providerSession::textMessage),
            )
            val receive = providerSession.receive()
                .filter { it.type == WebSocketMessage.Type.TEXT }
                .map { it.payloadAsText }
                .concatMap { raw ->
                    diagnostics.observeProviderEvent(raw)
                    val observed = runCatching { turnController.observeProviderEvent(raw) }
                    val observationFailure = observed.exceptionOrNull()
                    if (
                        observationFailure is VoiceTutorProviderOutputBufferClearedException ||
                        observationFailure is VoiceTutorProviderIncompleteResponseException
                    ) {
                        return@concatMap mono {
                            onProviderEvent(raw, false, true)
                        }.then(Mono.error<Void>(observationFailure))
                    }
                    if (observationFailure != null) {
                        return@concatMap Mono.error<Void>(observationFailure)
                    }
                    val disposition = observed.getOrThrow()
                    mono {
                        onProviderEvent(
                            raw,
                            disposition.persist,
                            disposition.forwardToClient,
                        )
                    }.then()
                }
                .then()
            val ready = mono {
                onProviderEvent(
                    SIDEBAND_READY_PAYLOAD,
                    false,
                    true,
                )
                turnController.startOpeningResponse()
            }.then()
            // Subscribe both provider directions before telling the mobile peer to
            // enable its microphone. The ready branch intentionally never completes
            // after its callback, so it cannot win and tear down the relay.
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
            "audio" to linkedMapOf(
                "input" to linkedMapOf(
                    "transcription" to mapOf("model" to "gpt-4o-mini-transcribe"),
                    "turn_detection" to linkedMapOf(
                        "type" to "server_vad",
                        "create_response" to false,
                        "interrupt_response" to false,
                    ),
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
internal class VoiceTutorWebRtcProviderException(message: String) : RuntimeException(message)

private const val MAX_SDP_BYTES = 65_536
private const val MAX_SDP_LINES = 512
private const val MAX_SDP_LINE_CHARACTERS = 4_096
private val SDP_SHA256_FINGERPRINT = Regex("a=fingerprint:sha-256(?: [0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){31})")
private val OPENAI_CALL_ID_PATTERN = Regex("rtc_[A-Za-z0-9_-]{1,187}")
private val OPENAI_CALL_LOCATION_PATTERN = Regex("/v1/realtime/calls/(rtc_[A-Za-z0-9_-]{1,187})")
