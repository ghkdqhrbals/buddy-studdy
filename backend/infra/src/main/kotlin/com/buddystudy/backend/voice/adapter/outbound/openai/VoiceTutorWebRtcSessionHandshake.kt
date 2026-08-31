package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolDefinition
import com.buddystudy.study.domain.QuestionLanguage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Applies and verifies server-owned turn control and input language on the GA call. */
internal class VoiceTutorWebRtcSessionHandshake(
    callId: String,
    private val confirmationTimeout: Duration,
    private val onConfiguration: (VoiceTutorWebRtcConfigurationSnapshot) -> Unit = ::logWebRtcConfiguration,
    expectedTools: List<Map<String, Any?>> = emptyList(),
    transcriptionLanguage: String,
) {
    private val callRef = voiceTutorCallReference(callId)
    private val updateRequested = AtomicBoolean()
    private val confirmed = Sinks.one<Void>()
    private val tools = JsonMapperProvider.mapper.valueToTree<JsonNode>(expectedTools)
    private val transcription = voiceTutorInputTranscription(transcriptionLanguage)

    init {
        val names = tools.map { it.path("name").asText() }
        require(names.size == names.toSet().size && tools.all {
            it.path("type").asText() == "function" && it.path("name").isTextual &&
                it.path("name").asText().isNotBlank() && it.path("description").isTextual &&
                it.path("parameters").isObject
        }) { "Voice Tutor tools must be uniquely named function definitions." }
    }

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
                            "tools" to tools,
                            "tool_choice" to "auto",
                            "audio" to mapOf(
                                "input" to mapOf(
                                    "transcription" to transcription,
                                    "turn_detection" to voiceTutorManualWebRtcTurnDetection(),
                                ),
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
        val toolsVerified = verifiesTools(session)
        val effectiveLanguage = audioInput.path("transcription").path("language")
        val transcriptionLanguageVerified = effectiveLanguage.isTextual &&
            effectiveLanguage.textValue() == transcription.getValue("language")
        val verified = requested && eventType == "session.updated" &&
            schema == VoiceTutorWebRtcConfigurationSchema.GA && sessionType == "realtime" &&
            turnDetection.isNull && toolsVerified && transcriptionLanguageVerified

        onConfiguration(
            VoiceTutorWebRtcConfigurationSnapshot(
                callRef, eventType, sessionType, schema, turnDetectionType,
                createResponse, interruptResponse, requested, verified,
                expectedToolCount = tools.size(),
                effectiveToolCount = session.path("tools").takeIf { it.isArray }?.size() ?: -1,
                toolsVerified = toolsVerified,
                expectedTranscriptionLanguage = transcription.getValue("language"),
                effectiveTranscriptionLanguage = effectiveLanguage.safeConfigurationName(QuestionLanguage.supported),
                transcriptionLanguageVerified = transcriptionLanguageVerified,
            ),
        )
        if (requested && eventType == "session.updated") {
            if (!verified) throw VoiceTutorWebRtcSessionConfigurationException()
            confirmed.tryEmitEmpty()
        }
        return true
    }

    private fun verifiesTools(session: JsonNode): Boolean {
        val effective = session.path("tools")
        val choice = session.path("tool_choice")
        if (tools.isEmpty) {
            // Older callers did not send tools. Missing fields remain compatible,
            // but an unexpected provider tool never acquires execution authority.
            return (effective.isMissingNode || (effective.isArray && effective.isEmpty)) &&
                (choice.isMissingNode || (choice.isTextual && choice.textValue() == "auto"))
        }
        if (!effective.isArray || effective.size() != tools.size() ||
            !choice.isTextual || choice.textValue() != "auto"
        ) return false
        val effectiveByName = effective.associateBy { it.path("name").asText() }
        if (effectiveByName.size != effective.size()) return false
        return tools.all { expected ->
            val actual = effectiveByName[expected.path("name").asText()] ?: return@all false
            actual.path("type").isTextual && actual.path("type").textValue() == "function" &&
                actual.path("name") == expected.path("name") &&
                actual.path("description") == expected.path("description") &&
                runCatching {
                    actual.path("parameters").equals(JSON_SCHEMA_VALUE_COMPARATOR, expected.path("parameters"))
                }.getOrDefault(false)
        }
    }

    private companion object {
        val SESSION_TYPES = setOf("realtime", "transcription")
        val TURN_DETECTION_TYPES = setOf("server_vad", "semantic_vad")
        // JSON object key order and numeric representation are not schema changes.
        // Property/required/enum contents and all validation constraints must match.
        val JSON_SCHEMA_VALUE_COMPARATOR = Comparator<JsonNode> { left, right ->
            when {
                left.isNumber && right.isNumber -> left.decimalValue().compareTo(right.decimalValue())
                left == right -> 0
                else -> 1
            }
        }
    }
}

/** The MCP catalog remains the single source of function names, descriptions, and schemas. */
internal fun voiceTutorRealtimeFunctionTools(definitions: List<VoiceTutorMcpToolDefinition>): List<Map<String, Any?>> =
    definitions.map { definition ->
        linkedMapOf(
            "type" to "function",
            "name" to definition.name,
            "description" to definition.description,
            "parameters" to definition.parameters,
        )
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
    val expectedToolCount: Int = 0,
    val effectiveToolCount: Int = -1,
    val toolsVerified: Boolean = true,
    val expectedTranscriptionLanguage: String = "none",
    val effectiveTranscriptionLanguage: String = "none",
    val transcriptionLanguageVerified: Boolean = false,
)

internal class VoiceTutorWebRtcSessionConfigurationException :
    RuntimeException("Provider session did not confirm server-owned Voice Tutor turns, tools and input language.")

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
            "turnDetectionType={} createResponse={} interruptResponse={} updateRequested={} verified={} " +
            "expectedToolCount={} effectiveToolCount={} toolsVerified={} " +
            "expectedTranscriptionLanguage={} effectiveTranscriptionLanguage={} transcriptionLanguageVerified={}",
        configuration.callRef,
        configuration.eventType,
        configuration.sessionType,
        configuration.schema,
        configuration.turnDetectionType,
        configuration.createResponse ?: "none",
        configuration.interruptResponse ?: "none",
        configuration.updateRequested,
        configuration.verified,
        configuration.expectedToolCount,
        configuration.effectiveToolCount,
        configuration.toolsVerified,
        configuration.expectedTranscriptionLanguage,
        configuration.effectiveTranscriptionLanguage,
        configuration.transcriptionLanguageVerified,
    )
}
