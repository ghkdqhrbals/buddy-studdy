package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Applies and verifies server-owned turn control on the already-created GA call. */
internal class VoiceTutorWebRtcSessionHandshake(
    callId: String,
    private val confirmationTimeout: Duration,
    private val onConfiguration: (VoiceTutorWebRtcConfigurationSnapshot) -> Unit = ::logWebRtcConfiguration,
) {
    private val callRef = voiceTutorCallReference(callId)
    private val updateRequested = AtomicBoolean()
    private val confirmed = Sinks.one<Void>()

    fun initialProviderEvents(): Flux<String> = Flux.defer {
        if (!updateRequested.compareAndSet(false, true)) {
            Flux.empty()
        } else {
            Flux.just(
                JsonMapperProvider.mapper.writeValueAsString(
                    linkedMapOf(
                        "event_id" to "buddystudy-internal-session-config-${UUID.randomUUID()}",
                        "type" to "session.update",
                        "session" to linkedMapOf(
                            "type" to "realtime",
                            "audio" to mapOf(
                                "input" to mapOf("turn_detection" to voiceTutorManualWebRtcTurnDetection()),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    fun awaitConfirmation(): Mono<Void> = confirmed.asMono().timeout(
        confirmationTimeout,
        Mono.error(VoiceTutorWebRtcSessionConfigurationTimeoutException()),
    )

    /** Consumes configuration events locally; full session contents never leave this gate. */
    fun observeProviderEvent(raw: String): Boolean {
        val event = runCatching { JsonMapperProvider.mapper.readTree(raw) }.getOrNull() ?: return false
        val eventType = event.path("type").asText()
        if (eventType != "session.created" && eventType != "session.updated") return false

        val session = event.path("session")
        val audioInput = session.path("audio").path("input")
        val schema = when {
            audioInput.has("turn_detection") -> VoiceTutorWebRtcConfigurationSchema.GA
            session.has("turn_detection") -> VoiceTutorWebRtcConfigurationSchema.LEGACY
            else -> VoiceTutorWebRtcConfigurationSchema.MISSING
        }
        val turnDetection = if (schema == VoiceTutorWebRtcConfigurationSchema.GA) {
            audioInput.path("turn_detection")
        } else {
            session.path("turn_detection")
        }
        val sessionType = session.path("type").safeConfigurationName(SESSION_TYPES)
        val turnDetectionType = if (schema == VoiceTutorWebRtcConfigurationSchema.GA && turnDetection.isNull) {
            "manual"
        } else {
            turnDetection.path("type").safeConfigurationName(TURN_DETECTION_TYPES)
        }
        val createResponse = turnDetection.path("create_response").explicitBoolean()
        val interruptResponse = turnDetection.path("interrupt_response").explicitBoolean()
        val requested = updateRequested.get()
        val verified = requested && eventType == "session.updated" &&
            schema == VoiceTutorWebRtcConfigurationSchema.GA && sessionType == "realtime" &&
            turnDetection.isNull

        onConfiguration(
            VoiceTutorWebRtcConfigurationSnapshot(
                callRef, eventType, sessionType, schema, turnDetectionType,
                createResponse, interruptResponse, requested, verified,
            ),
        )
        if (requested && eventType == "session.updated") {
            if (!verified) throw VoiceTutorWebRtcSessionConfigurationException()
            confirmed.tryEmitEmpty()
        }
        return true
    }

    private companion object {
        val SESSION_TYPES = setOf("realtime", "transcription")
        val TURN_DETECTION_TYPES = setOf("server_vad", "semantic_vad")
    }
}

/**
 * WebRTC VAD can clear media even when response interruption is disabled.
 * Keep media duplex, but accept utterance boundaries from the authenticated
 * local-VAD client and let the server commit input and schedule responses.
 * A NullNode preserves an explicit JSON null even with NON_NULL map inclusion.
 */
internal fun voiceTutorManualWebRtcTurnDetection(): JsonNode = NullNode.instance

internal enum class VoiceTutorWebRtcConfigurationSchema { GA, LEGACY, MISSING }

internal data class VoiceTutorWebRtcConfigurationSnapshot(
    val callRef: String,
    val eventType: String,
    val sessionType: String,
    val schema: VoiceTutorWebRtcConfigurationSchema,
    val turnDetectionType: String,
    val createResponse: Boolean?,
    val interruptResponse: Boolean?,
    val updateRequested: Boolean,
    val verified: Boolean,
)

internal class VoiceTutorWebRtcSessionConfigurationException :
    RuntimeException("Provider session did not confirm server-owned Voice Tutor turns.")

internal class VoiceTutorWebRtcSessionConfigurationTimeoutException :
    RuntimeException("Provider session configuration acknowledgement timed out.")

private fun JsonNode.explicitBoolean(): Boolean? = takeIf { it.isBoolean }?.booleanValue()

private fun JsonNode.safeConfigurationName(allowed: Set<String>): String = when {
    isMissingNode || isNull -> "none"
    isTextual && textValue() in allowed -> textValue()
    else -> "other"
}

private val configurationLogger = LoggerFactory.getLogger(
    "com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorWebRtcSessionHandshake",
)

private fun logWebRtcConfiguration(configuration: VoiceTutorWebRtcConfigurationSnapshot) {
    configurationLogger.info(
        "voice_tutor_sideband_configuration callRef={} eventType={} sessionType={} schema={} " +
            "turnDetectionType={} createResponse={} interruptResponse={} updateRequested={} verified={}",
        configuration.callRef,
        configuration.eventType,
        configuration.sessionType,
        configuration.schema,
        configuration.turnDetectionType,
        configuration.createResponse ?: "none",
        configuration.interruptResponse ?: "none",
        configuration.updateRequested,
        configuration.verified,
    )
}
