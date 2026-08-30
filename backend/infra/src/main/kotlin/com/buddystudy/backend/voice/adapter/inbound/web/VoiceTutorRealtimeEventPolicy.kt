package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID

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
        if (type == "conversation.item.truncate") {
            validateTruncation(node)
        }
        return type in ALLOWED_CLIENT_EVENTS
    }

    fun providerEventsForBargeIn(raw: String): List<String> {
        if (raw.length > MAX_CLIENT_EVENT_CHARACTERS) {
            throw VoiceTutorClientProtocolException("Voice Tutor client event exceeds the allowed size.")
        }
        val node = clientEvent(raw)
        if (node.path("type").asText() != CLIENT_BARGE_IN_EVENT) {
            throw VoiceTutorClientProtocolException("Unsupported Voice Tutor client event.")
        }
        val cancelNode = node.path("cancelResponse")
        if (!cancelNode.isBoolean) {
            throw VoiceTutorClientProtocolException("Voice Tutor barge-in cancellation flag is required.")
        }
        val cancelResponse = cancelNode.booleanValue()
        val hasTruncation = !node.path("itemId").isMissingNode ||
            !node.path("contentIndex").isMissingNode ||
            !node.path("audioEndMs").isMissingNode
        val truncation = if (hasTruncation) {
            val normalized = mapper.createObjectNode().apply {
                put("item_id", node.path("itemId").asText())
                set<JsonNode>("content_index", node.path("contentIndex"))
                set<JsonNode>("audio_end_ms", node.path("audioEndMs"))
            }
            validateTruncation(normalized)
            normalized
        } else {
            null
        }
        if (!cancelResponse && truncation == null) {
            throw VoiceTutorClientProtocolException("Voice Tutor barge-in requires a provider action.")
        }

        return buildList {
            if (cancelResponse) {
                add(
                    mapper.writeValueAsString(
                        linkedMapOf(
                            "event_id" to internalEventId("cancel"),
                            "type" to "response.cancel",
                        ),
                    ),
                )
            }
            truncation?.let {
                add(
                    mapper.writeValueAsString(
                        linkedMapOf(
                            "event_id" to internalEventId("truncate"),
                            "type" to "conversation.item.truncate",
                            "item_id" to it.path("item_id").asText(),
                            "content_index" to it.path("content_index").intValue(),
                            "audio_end_ms" to it.path("audio_end_ms").intValue(),
                        ),
                    ),
                )
            }
        }
    }

    fun internalResponseCancelEvent(action: String): String = mapper.writeValueAsString(
        linkedMapOf(
            "event_id" to internalEventId(action.take(32).ifBlank { "cancel" }),
            "type" to "response.cancel",
        ),
    )

    fun providerDecision(raw: String, sessionId: String, serverTime: Instant): ProviderEventDecision {
        val node = runCatching { mapper.readTree(raw) }.getOrNull()
            ?: return providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
        return when (val type = node.path("type").asText()) {
            "error" -> if (isExpectedInternalControlError(node)) {
                ProviderEventDecision(payload = null)
            } else {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_ERROR")
            }
            "response.output_audio.delta" -> if (validProviderAudioDelta(node)) {
                ProviderEventDecision(raw)
            } else {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            }
            in TRANSCRIPT_DELTA_PROVIDER_EVENTS -> if (
                validProviderText(node, "delta", MAX_TRANSCRIPT_DELTA_CHARACTERS)
            ) {
                ProviderEventDecision(raw)
            } else {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            }
            in TRANSCRIPT_DONE_PROVIDER_EVENTS -> if (
                validProviderText(node, "transcript", MAX_TRANSCRIPT_CHARACTERS)
            ) {
                ProviderEventDecision(raw)
            } else {
                providerFailure(sessionId, serverTime, "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR")
            }
            in PASSTHROUGH_PROVIDER_EVENTS -> ProviderEventDecision(raw)
            "response.created", "response.done" -> ProviderEventDecision(
                mapper.writeValueAsString(
                    linkedMapOf(
                        "type" to type,
                        "response" to linkedMapOf(
                            "id" to node.path("response").path("id").asText(),
                            "status" to node.path("response").path("status").asText(),
                        ),
                        VoiceTutorRealtimeContract.TUTOR_INTERVENTION_FIELD to (
                            type == "response.created" &&
                                node.path("response").path("metadata")
                                    .path(VoiceTutorRealtimeContract.TURN_METADATA_KEY).asText() ==
                                VoiceTutorRealtimeContract.CONTINUOUS_INTERVENTION_TURN
                            ),
                    ),
                ),
            )
            else -> ProviderEventDecision(payload = null)
        }
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

    private fun validateTruncation(node: JsonNode) {
        val itemIdNode = node.path("item_id")
        val itemId = itemIdNode.asText()
        if (!itemIdNode.isTextual || !ITEM_ID_PATTERN.matches(itemId)) {
            throw VoiceTutorClientProtocolException("Voice Tutor truncation item id is invalid.")
        }
        val contentIndex = node.path("content_index")
        if (!contentIndex.isIntegralNumber || contentIndex.longValue() !in 0L..MAX_CONTENT_INDEX.toLong()) {
            throw VoiceTutorClientProtocolException("Voice Tutor truncation content index is invalid.")
        }
        val audioEndMs = node.path("audio_end_ms")
        if (!audioEndMs.isIntegralNumber || audioEndMs.longValue() !in 0L..MAX_AUDIO_END_MILLISECONDS) {
            throw VoiceTutorClientProtocolException("Voice Tutor truncation audio position is invalid.")
        }
    }

    private fun isExpectedInternalControlError(node: JsonNode): Boolean =
        node.path("error").path("event_id").asText().startsWith(INTERNAL_CONTROL_EVENT_PREFIX)

    private fun validProviderAudioDelta(node: JsonNode): Boolean {
        val delta = node.path("delta")
        if (!delta.isTextual || delta.asText().isEmpty()) return false
        return try {
            Base64.getDecoder().decode(delta.asText()).size <= MAX_PROVIDER_AUDIO_DELTA_BYTES
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun validProviderText(node: JsonNode, field: String, maxCharacters: Int): Boolean {
        val value = node.path(field)
        return value.isTextual && value.asText().length <= maxCharacters
    }

    private fun internalEventId(action: String): String =
        "$INTERNAL_CONTROL_EVENT_PREFIX$action-${UUID.randomUUID()}"

    data class ProviderEventDecision(
        val payload: String?,
        val terminate: Boolean = false,
    )

    companion object {
        const val CLIENT_END_EVENT = "buddystudy.voice.session.end"
        const val CLIENT_HEARTBEAT_EVENT = "buddystudy.voice.heartbeat"
        const val CLIENT_BARGE_IN_EVENT = "buddystudy.voice.barge-in"
        const val INTERNAL_CONTROL_EVENT_PREFIX = "buddystudy-internal-"
        const val MAX_CLIENT_EVENT_CHARACTERS = 65_536
        private const val MAX_CONTENT_INDEX = 64
        private const val MAX_AUDIO_END_MILLISECONDS = 3_600_000L
        private const val MAX_PROVIDER_AUDIO_DELTA_BYTES = 32_768
        private const val MAX_TRANSCRIPT_DELTA_CHARACTERS = 4_096
        private const val MAX_TRANSCRIPT_CHARACTERS = 32_000
        private val ITEM_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,191}")
        private val ALLOWED_CLIENT_EVENTS = setOf(
            "input_audio_buffer.append",
            "input_audio_buffer.commit",
            "input_audio_buffer.clear",
            "response.cancel",
            "conversation.item.truncate",
        )
        private val LOCAL_CLIENT_EVENTS = setOf(CLIENT_END_EVENT, CLIENT_HEARTBEAT_EVENT, CLIENT_BARGE_IN_EVENT)
        private val TRANSCRIPT_DELTA_PROVIDER_EVENTS = setOf(
            "response.output_audio_transcript.delta",
            "conversation.item.input_audio_transcription.delta",
        )
        private val TRANSCRIPT_DONE_PROVIDER_EVENTS = setOf(
            "response.output_audio_transcript.done",
            "conversation.item.input_audio_transcription.completed",
        )
        private val PASSTHROUGH_PROVIDER_EVENTS = setOf(
            "response.output_audio.done",
            "input_audio_buffer.speech_started",
            "input_audio_buffer.speech_stopped",
        )
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
            VoiceTutorRealtimeEventPolicy.CLIENT_BARGE_IN_EVENT,
            "input_audio_buffer.commit",
            "input_audio_buffer.clear",
            "response.cancel",
            "conversation.item.truncate",
        )
    }
}

internal class VoiceTutorClientProtocolException(message: String) : RuntimeException(message)
internal class VoiceTutorProviderReportedException : RuntimeException("Voice Tutor provider reported a realtime failure.")
