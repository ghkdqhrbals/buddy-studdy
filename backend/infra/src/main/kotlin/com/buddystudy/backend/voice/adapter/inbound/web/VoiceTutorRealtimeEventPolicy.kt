package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata
import com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorProviderErrorDisposition
import com.buddystudy.backend.voice.adapter.outbound.openai.classifyRealtimeProviderError
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Base64

internal class VoiceTutorRealtimeEventPolicy(
    private val mapper: ObjectMapper = JsonMapperProvider.mapper,
) {
    fun eventType(raw: String): String =
        runCatching { mapper.readTree(raw).path("type").asText() }.getOrDefault("")

    fun shouldForwardClientEvent(raw: String): Boolean {
        if (raw.length > MAX_CLIENT_EVENT_CHARACTERS) {
            throw VoiceTutorClientProtocolException("Voice Tutor client event exceeds the allowed size.")
        }
        val node = clientEvent(raw)
        val type = node.path("type").asText()
        if (type !in ALLOWED_CLIENT_EVENTS && type !in LOCAL_CLIENT_EVENTS) {
            throw VoiceTutorClientProtocolException("Unsupported Voice Tutor client event.")
        }
        if (node.path("event_id").asText().startsWith(INTERNAL_CONTROL_EVENT_PREFIX)) {
            throw VoiceTutorClientProtocolException("Voice Tutor client event id is reserved.")
        }
        if (type == VoiceTutorRealtimeContract.PLAYBACK_COMPLETED_EVENT ||
            type == VoiceTutorRealtimeContract.PLAYOUT_DRAINED_EVENT
        ) {
            validatePlaybackCompletion(node)
        }
        if (type == VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT ||
            type == VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT
        ) {
            val sequence = node.path("sequence")
            if (!sequence.isIntegralNumber || !sequence.canConvertToLong() || sequence.longValue() <= 0) {
                throw VoiceTutorClientProtocolException("Voice Tutor speech sequence is invalid.")
            }
        }
        return type in ALLOWED_CLIENT_EVENTS
    }

    fun providerDecision(
        raw: String,
        sessionId: String,
        serverTime: Instant,
        transport: VoiceTutorProviderTransport = VoiceTutorProviderTransport.LEGACY_PCM_RELAY,
    ): ProviderEventDecision {
        val node = runCatching { mapper.readTree(raw) }.getOrNull()
            ?: return providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
        if (!node.isObject || node.path("type").asText().isBlank()) {
            return providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
        }
        return when (val type = node.path("type").asText()) {
            "error" -> when (classifyRealtimeProviderError(node)) {
                VoiceTutorProviderErrorDisposition.RECOVERABLE -> ProviderEventDecision(payload = null)
                VoiceTutorProviderErrorDisposition.SESSION_FATAL ->
                    providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_ERROR")
                VoiceTutorProviderErrorDisposition.PROTOCOL_INVALID ->
                    providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            }
            "response.output_audio.delta" -> if (validProviderAudioDelta(node)) {
                ProviderEventDecision(
                    payload = raw.takeIf { transport == VoiceTutorProviderTransport.LEGACY_PCM_RELAY },
                )
            } else {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            }
            "response.output_audio.done" -> if (validProviderResponseId(node.path("response_id"))) {
                ProviderEventDecision(raw)
            } else {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            }
            in TUTOR_TRANSCRIPT_DELTA_PROVIDER_EVENTS -> if (
                validProviderResponseId(node.path("response_id")) &&
                validProviderText(node, "delta", MAX_TRANSCRIPT_DELTA_CHARACTERS)
            ) {
                ProviderEventDecision(withoutInternalTranscriptMetadata(node, raw))
            } else {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            }
            in TUTOR_TRANSCRIPT_DONE_PROVIDER_EVENTS -> if (
                validProviderResponseId(node.path("response_id")) &&
                validProviderText(node, "transcript", MAX_TRANSCRIPT_CHARACTERS)
            ) {
                ProviderEventDecision(withoutInternalTranscriptMetadata(node, raw))
            } else {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            }
            in USER_TRANSCRIPT_DELTA_PROVIDER_EVENTS -> if (
                validProviderText(node, "delta", MAX_TRANSCRIPT_DELTA_CHARACTERS)
            ) {
                ProviderEventDecision(withoutInternalTranscriptMetadata(node, raw))
            } else {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            }
            in USER_TRANSCRIPT_DONE_PROVIDER_EVENTS -> if (
                validProviderText(node, "transcript", MAX_TRANSCRIPT_CHARACTERS)
            ) {
                ProviderEventDecision(withoutInternalTranscriptMetadata(node, raw))
            } else {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            }
            VoiceTutorRealtimeContract.INPUT_RETRY_EVENT -> if (
                transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND
            ) {
                // A safe server-owned hint, not a provider failure or call end.
                // Never forward provider bodies/classifier text to the UI.
                val payload = linkedMapOf<String, Any>("type" to VoiceTutorRealtimeContract.INPUT_RETRY_EVENT)
                node.path(VoiceTutorRealtimeContract.ABANDONED_RESPONSE_ID_FIELD)
                    .takeIf(::validProviderResponseId)
                    ?.asText()
                    ?.let { payload[VoiceTutorRealtimeContract.ABANDONED_RESPONSE_ID_FIELD] = it }
                ProviderEventDecision(
                    mapper.writeValueAsString(payload),
                )
            } else {
                ProviderEventDecision(payload = null)
            }
            VoiceTutorRealtimeContract.SIDEBAND_READY_EVENT -> if (
                transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND
            ) {
                ProviderEventDecision(
                    mapper.writeValueAsString(mapOf("type" to VoiceTutorRealtimeContract.SIDEBAND_READY_EVENT)),
                )
            } else {
                ProviderEventDecision(payload = null)
            }
            VoiceTutorRealtimeContract.STUDY_TREE_CHANGED_EVENT -> {
                val studyId = node.path("studyId")
                if (transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND &&
                    studyId.isIntegralNumber && studyId.canConvertToLong() && studyId.longValue() > 0
                ) {
                    ProviderEventDecision(
                        mapper.writeValueAsString(
                            mapOf("type" to type, "studyId" to studyId.longValue()),
                        ),
                    )
                } else {
                    ProviderEventDecision(payload = null)
                }
            }
            VoiceTutorRealtimeContract.STUDY_FOCUSED_EVENT -> {
                val focus = node.path("focus")
                val parent = focus.path("parentStudyId")
                fun positive(field: String) = focus.path(field).let {
                    it.isIntegralNumber && it.canConvertToLong() && it.longValue() > 0
                }
                if (transport != VoiceTutorProviderTransport.WEBRTC_SIDEBAND ||
                    !(focus.isNull || (focus.isObject && positive("studyId") && positive("revision") &&
                        (parent.isNull || positive("parentStudyId")) && focus.path("topic").isTextual &&
                        focus.path("topic").asText().isNotBlank() && focus.path("topic").asText().length <= 255 &&
                        focus.path("difficulty").isIntegralNumber && focus.path("difficulty").asInt() in 1..10))
                ) ProviderEventDecision(payload = null)
                else ProviderEventDecision(mapper.writeValueAsString(mapOf("type" to type, "focus" to focus)))
            }
            VoiceTutorRealtimeContract.PAUSE_STATE_EVENT -> {
                val sequence = node.path("sequence")
                val paused = node.path("paused")
                if (transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND &&
                    sequence.isIntegralNumber && sequence.canConvertToLong() && sequence.longValue() > 0 &&
                    paused.isBoolean
                ) {
                    ProviderEventDecision(
                        mapper.writeValueAsString(mapOf(
                            "type" to type,
                            "sequence" to sequence.longValue(),
                            "paused" to paused.booleanValue(),
                        )),
                    )
                } else {
                    ProviderEventDecision(payload = null)
                }
            }
            in WEBRTC_OUTPUT_BUFFER_BOUNDARY_EVENTS -> if (
                transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND &&
                validProviderResponseId(node.path("response_id"))
            ) {
                ProviderEventDecision(
                    mapper.writeValueAsString(
                        linkedMapOf(
                            "type" to type,
                            "response_id" to node.path("response_id").asText(),
                        ),
                    ),
                )
            } else if (transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND) {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            } else {
                ProviderEventDecision(payload = null)
            }
            "output_audio_buffer.cleared" -> if (
                transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND &&
                validProviderResponseId(node.path("response_id"))
            ) {
                // The turn controller owns one bounded regeneration. This is
                // never a whole-call failure on its own.
                ProviderEventDecision(payload = null)
            } else if (transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND) {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            } else {
                ProviderEventDecision(payload = null)
            }
            in PASSTHROUGH_PROVIDER_EVENTS -> ProviderEventDecision(raw)
            "response.created", "response.done" -> responseDecision(
                node,
                type,
                sessionId,
                serverTime,
                transport,
            )
            else -> ProviderEventDecision(payload = null)
        }
    }

    private fun withoutInternalTranscriptMetadata(node: JsonNode, raw: String): String {
        if (!node.has(VoiceTutorTranscriptMetadata.ACCEPTED_AT_EPOCH_MILLIS) &&
            !node.has(VoiceTutorTranscriptMetadata.LESSON_REVISION) &&
            !node.has(VoiceTutorTranscriptMetadata.STUDY_QUESTION_PROVIDER_ITEM_ID) &&
            !node.has(VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_ID) &&
            !node.has(VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_IDS) &&
            !node.has(VoiceTutorTranscriptMetadata.ASKED_STUDY_QUESTION) &&
            !node.has(VoiceTutorTranscriptMetadata.IS_STUDY_QUESTION) &&
            !node.has(VoiceTutorTranscriptMetadata.POST_CALL_EVIDENCE) &&
            !node.has(VoiceTutorTranscriptMetadata.CONVERSATION_SEQUENCE)
        ) return raw
        val publicNode = node.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        publicNode.remove(VoiceTutorTranscriptMetadata.ACCEPTED_AT_EPOCH_MILLIS)
        publicNode.remove(VoiceTutorTranscriptMetadata.LESSON_REVISION)
        publicNode.remove(VoiceTutorTranscriptMetadata.STUDY_QUESTION_PROVIDER_ITEM_ID)
        publicNode.remove(VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_ID)
        publicNode.remove(VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_IDS)
        publicNode.remove(VoiceTutorTranscriptMetadata.ASKED_STUDY_QUESTION)
        publicNode.remove(VoiceTutorTranscriptMetadata.IS_STUDY_QUESTION)
        publicNode.remove(VoiceTutorTranscriptMetadata.POST_CALL_EVIDENCE)
        publicNode.remove(VoiceTutorTranscriptMetadata.CONVERSATION_SEQUENCE)
        return mapper.writeValueAsString(publicNode)
    }

    private fun responseDecision(
        node: JsonNode,
        type: String,
        sessionId: String,
        serverTime: Instant,
        transport: VoiceTutorProviderTransport,
    ): ProviderEventDecision {
        val response = node.path("response")
        if (!validProviderResponseId(response.path("id"))) {
            return providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
        }
        val status = response.path("status").asText()
        if (type == "response.done" && status !in RESPONSE_DONE_STATUSES) {
            return providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
        }
        if (
            type == "response.done" &&
            transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND &&
            status != "completed"
        ) {
            // Cancelled/incomplete/failed are response-local. The sideband
            // turn controller retries once, then asks for fresh learner input.
            return ProviderEventDecision(payload = null)
        }
        if (type == "response.done" && status == "failed") {
            return providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_ERROR")
        }
        val payload = linkedMapOf<String, Any>(
            "type" to type,
            "response" to linkedMapOf(
                "id" to response.path("id").asText(),
                "status" to status,
            ),
            VoiceTutorRealtimeContract.TUTOR_INTERVENTION_FIELD to (
                type == "response.created" &&
                    response.path("metadata").path(VoiceTutorRealtimeContract.TURN_METADATA_KEY).asText() ==
                    VoiceTutorRealtimeContract.CONTINUOUS_INTERVENTION_TURN
                ),
        )
        if (type == "response.created") {
            val marker = response.path("metadata").path(VoiceTutorRealtimeContract.QUOTA_NOTICE_METADATA_KEY)
            payload[VoiceTutorRealtimeContract.QUOTA_EXHAUSTION_NOTICE_FIELD] =
                marker.isBoolean && marker.booleanValue()
        }
        return ProviderEventDecision(mapper.writeValueAsString(payload))
    }

    private fun providerFailure(sessionId: String, serverTime: Instant, code: String) = ProviderEventDecision(
        payload = mapper.writeValueAsString(
            linkedMapOf(
                "type" to "buddystudy.voice.error",
                "sessionId" to sessionId,
                "serverTime" to serverTime,
                "code" to code,
                "message" to "Voice Tutor provider ended the realtime session.",
                "retryable" to true,
            ),
        ),
        terminate = true,
    )

    private fun clientEvent(raw: String): JsonNode {
        val node = runCatching { mapper.readTree(raw) }.getOrNull()
            ?: throw VoiceTutorClientProtocolException("Voice Tutor client event must be valid JSON.")
        if (!node.isObject || node.path("type").asText().isBlank()) {
            throw VoiceTutorClientProtocolException("Voice Tutor client event type is required.")
        }
        return node
    }

    private fun validatePlaybackCompletion(node: JsonNode) {
        val responseId = node.path("responseId")
        if (!responseId.isTextual || !PROVIDER_ID_PATTERN.matches(responseId.asText())) {
            throw VoiceTutorClientProtocolException("Voice Tutor playback response id is invalid.")
        }
    }

    private fun validProviderAudioDelta(node: JsonNode): Boolean {
        if (!validProviderResponseId(node.path("response_id"))) return false
        val delta = node.path("delta")
        if (!delta.isTextual || delta.asText().isEmpty()) return false
        return try {
            Base64.getDecoder().decode(delta.asText()).size <= MAX_PROVIDER_AUDIO_DELTA_BYTES
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun validProviderResponseId(node: JsonNode): Boolean =
        node.isTextual && PROVIDER_ID_PATTERN.matches(node.asText())

    private fun validProviderText(node: JsonNode, field: String, maxCharacters: Int): Boolean {
        val value = node.path(field)
        return value.isTextual && value.asText().length <= maxCharacters
    }

    data class ProviderEventDecision(
        val payload: String?,
        val terminate: Boolean = false,
    )

    companion object {
        const val CLIENT_END_EVENT = "buddystudy.voice.session.end"
        const val CLIENT_HEARTBEAT_EVENT = "buddystudy.voice.heartbeat"
        const val INTERNAL_CONTROL_EVENT_PREFIX = "buddystudy-internal-"
        const val MAX_CLIENT_EVENT_CHARACTERS = 65_536
        private const val MAX_PROVIDER_AUDIO_DELTA_BYTES = 32_768
        private const val MAX_TRANSCRIPT_DELTA_CHARACTERS = 4_096
        private const val MAX_TRANSCRIPT_CHARACTERS = 32_000
        private val PROVIDER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,191}")
        private val ALLOWED_CLIENT_EVENTS = setOf(
            "input_audio_buffer.append",
            "input_audio_buffer.commit",
        )
        private val LOCAL_CLIENT_EVENTS = setOf(
            CLIENT_END_EVENT,
            CLIENT_HEARTBEAT_EVENT,
            VoiceTutorRealtimeContract.PLAYBACK_COMPLETED_EVENT,
            VoiceTutorRealtimeContract.PLAYOUT_DRAINED_EVENT,
            VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT,
            VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT,
            VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT,
            VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT,
            VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT,
        )
        private val TUTOR_TRANSCRIPT_DELTA_PROVIDER_EVENTS = setOf(
            "response.output_audio_transcript.delta",
        )
        private val USER_TRANSCRIPT_DELTA_PROVIDER_EVENTS = setOf(
            "conversation.item.input_audio_transcription.delta",
        )
        private val TUTOR_TRANSCRIPT_DONE_PROVIDER_EVENTS = setOf(
            "response.output_audio_transcript.done",
        )
        private val USER_TRANSCRIPT_DONE_PROVIDER_EVENTS = setOf(
            "conversation.item.input_audio_transcription.completed",
        )
        private val PASSTHROUGH_PROVIDER_EVENTS = setOf(
            "input_audio_buffer.speech_started",
            "input_audio_buffer.speech_stopped",
        )
        private val WEBRTC_OUTPUT_BUFFER_BOUNDARY_EVENTS = setOf(
            "output_audio_buffer.started",
            "output_audio_buffer.stopped",
        )
        private val RESPONSE_DONE_STATUSES = setOf("completed", "cancelled", "incomplete", "failed")
    }
}

internal class VoiceTutorClientTrafficGuard(
    private val policy: VoiceTutorRealtimeEventPolicy,
    maxSessionSeconds: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    data class Decision(
        val forward: Boolean,
        val acceptedLocalEvent: Boolean = true,
        val acceptedAudioBytes: Long = 0,
    )

    private val mapper = JsonMapperProvider.mapper
    private val maxCumulativeAudioBytes = maxSessionSeconds.coerceIn(1, 3_600).toLong() * MAX_AUDIO_BYTES_PER_SECOND
    private var availableAudioBytes = MAX_AUDIO_BURST_BYTES.toDouble()
    private var cumulativeAudioBytes = 0L
    private var lastRefillNanos = nanoTime()
    private var lastHeartbeatNanos: Long? = null
    private var availableControlEvents = MAX_CONTROL_EVENT_BURST.toDouble()
    private var lastControlRefillNanos = lastRefillNanos

    @Synchronized
    fun inspect(raw: String): Decision {
        val forward = policy.shouldForwardClientEvent(raw)
        val type = policy.eventType(raw)
        if (type == VoiceTutorRealtimeEventPolicy.CLIENT_HEARTBEAT_EVENT) {
            val current = nanoTime()
            val previous = lastHeartbeatNanos
            if (previous != null && current - previous < MIN_HEARTBEAT_INTERVAL_NANOS) {
                return Decision(forward = false, acceptedLocalEvent = false)
            }
            lastHeartbeatNanos = current
            return Decision(forward = false)
        }
        if (type in RATE_LIMITED_CONTROL_EVENTS) {
            consumeControlEvent()
        }
        if (!forward || type != "input_audio_buffer.append") return Decision(forward)
        val audioNode = runCatching { mapper.readTree(raw).path("audio") }.getOrNull()
        if (audioNode == null || !audioNode.isTextual || audioNode.asText().isEmpty()) {
            throw VoiceTutorClientProtocolException("Voice Tutor audio payload is required.")
        }
        val audioBytes = try {
            Base64.getDecoder().decode(audioNode.asText()).size.toLong()
        } catch (_: IllegalArgumentException) {
            throw VoiceTutorClientProtocolException("Voice Tutor audio payload must be valid base64.")
        }
        if (audioBytes > MAX_AUDIO_BYTES_PER_EVENT) {
            throw VoiceTutorClientProtocolException("Voice Tutor audio chunk exceeds the allowed size.")
        }
        refill()
        if (audioBytes > availableAudioBytes) {
            throw VoiceTutorClientProtocolException("Voice Tutor audio is arriving faster than realtime.")
        }
        if (cumulativeAudioBytes + audioBytes > maxCumulativeAudioBytes) {
            throw VoiceTutorClientProtocolException("Voice Tutor audio allowance for this session was exceeded.")
        }
        availableAudioBytes -= audioBytes
        cumulativeAudioBytes += audioBytes
        return Decision(forward = true, acceptedAudioBytes = audioBytes)
    }

    private fun refill() {
        val current = nanoTime()
        val elapsed = (current - lastRefillNanos).coerceAtLeast(0).toDouble() / 1_000_000_000.0
        availableAudioBytes = (availableAudioBytes + elapsed * MAX_AUDIO_BYTES_PER_SECOND)
            .coerceAtMost(MAX_AUDIO_BURST_BYTES.toDouble())
        lastRefillNanos = current
    }

    private fun consumeControlEvent() {
        val current = nanoTime()
        val elapsed = (current - lastControlRefillNanos).coerceAtLeast(0).toDouble() / 1_000_000_000.0
        availableControlEvents = (availableControlEvents + elapsed * MAX_CONTROL_EVENTS_PER_SECOND)
            .coerceAtMost(MAX_CONTROL_EVENT_BURST.toDouble())
        lastControlRefillNanos = current
        if (availableControlEvents < 1) {
            throw VoiceTutorClientProtocolException("Voice Tutor control events are arriving too quickly.")
        }
        availableControlEvents -= 1
    }

    private companion object {
        const val MAX_AUDIO_BYTES_PER_SECOND = 48_000L
        const val MAX_AUDIO_BURST_BYTES = 12_000L
        const val MAX_AUDIO_BYTES_PER_EVENT = 32_768L
        const val MIN_HEARTBEAT_INTERVAL_NANOS = 8_000_000_000L
        const val MAX_CONTROL_EVENTS_PER_SECOND = 8.0
        const val MAX_CONTROL_EVENT_BURST = 8
        val RATE_LIMITED_CONTROL_EVENTS = setOf(
            VoiceTutorRealtimeContract.PLAYBACK_COMPLETED_EVENT,
            VoiceTutorRealtimeContract.PLAYOUT_DRAINED_EVENT,
            VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT,
            VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT,
            VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT,
            VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT,
            VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT,
            "input_audio_buffer.commit",
        )
    }
}

internal enum class VoiceTutorProviderTransport {
    LEGACY_PCM_RELAY,
    WEBRTC_SIDEBAND,
}

internal class VoiceTutorClientProtocolException(message: String) : RuntimeException(message)
internal class VoiceTutorProviderReportedException : RuntimeException("Voice Tutor provider reported a realtime failure.")
