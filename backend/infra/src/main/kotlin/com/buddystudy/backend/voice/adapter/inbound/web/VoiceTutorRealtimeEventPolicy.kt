package com.buddystudy.backend.voice.adapter.inbound.web

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata
import com.buddystudy.backend.voice.VoiceTutorUserInputContract
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
        if (type == VoiceTutorRealtimeContract.RESPONSE_WORD_FINISHED_EVENT &&
            (!validProviderResponseId(node.path("responseId")) || !validProviderResponseId(node.path("requestId")) ||
                node.fieldNames().asSequence().toSet() != setOf("type", "responseId", "requestId"))) {
            throw VoiceTutorClientProtocolException("Voice Tutor output boundary identity is invalid.")
        }
        if (type == VoiceTutorRealtimeContract.GRADING_REFRESH_EVENT &&
            (!validRecordId(node.path("recordId")) || node.fieldNames().asSequence().toSet() != setOf("type", "recordId"))) {
            throw VoiceTutorClientProtocolException("Voice Tutor grading record identity is invalid.")
        }
        if (type == VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT ||
            type == VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT
        ) {
            val sequence = node.path("sequence")
            if (!sequence.isIntegralNumber || !sequence.canConvertToLong() || sequence.longValue() <= 0) {
                throw VoiceTutorClientProtocolException("Voice Tutor speech sequence is invalid.")
            }
        }
        if (type in ANSWER_CLIENT_EVENTS) {
            if (!validAnswerId(node.path("answerId")) || !validRecordId(node.path("recordId")) ||
                (type == VoiceTutorRealtimeContract.ANSWER_SUBMIT_EVENT &&
                    !node.path("text").isTextual)
            ) throw VoiceTutorClientProtocolException("Voice Tutor reviewed answer is invalid.")
        }
        if (type in USER_INPUT_CLIENT_EVENTS && !VoiceTutorUserInputContract.validCorrelation(node)) {
            throw VoiceTutorClientProtocolException("Voice Tutor user input identity is invalid.")
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
            VoiceTutorRealtimeContract.RESPONSE_RECOVERING_EVENT -> {
                val sequence = node.path("sequence")
                if (transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND &&
                    validProviderResponseId(node.path("responseId")) && sequence.isIntegralNumber &&
                    sequence.canConvertToLong() && sequence.longValue() >= 0) {
                    ProviderEventDecision(mapper.writeValueAsString(mapOf("type" to type,
                        "responseId" to node.path("responseId").asText(), "sequence" to sequence.longValue())))
                } else ProviderEventDecision(payload = null)
            }
            VoiceTutorRealtimeContract.RESPONSE_FINISH_WORD_EVENT -> if (
                transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND &&
                validProviderResponseId(node.path("responseId")) && validProviderResponseId(node.path("requestId"))
            ) {
                ProviderEventDecision(mapper.writeValueAsString(mapOf("type" to type,
                    "responseId" to node.path("responseId").asText(), "requestId" to node.path("requestId").asText())))
            } else ProviderEventDecision(payload = null)
            VoiceTutorRealtimeContract.RESPONSE_INTERRUPTED_EVENT -> if (
                transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND &&
                validProviderResponseId(node.path("responseId"))
            ) {
                ProviderEventDecision(mapper.writeValueAsString(mapOf(
                    "type" to type, "responseId" to node.path("responseId").asText(),
                )))
            } else {
                ProviderEventDecision(payload = null)
            }
            VoiceTutorRealtimeContract.INPUT_RETRY_EVENT -> if (
                transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND
            ) {
                val sequence = node.path("sequence")
                if (node.has("sequence") && (!sequence.isIntegralNumber || !sequence.canConvertToLong() || sequence.longValue() < 0)) {
                    return ProviderEventDecision(payload = null)
                }
                // A safe server-owned hint, not a provider failure or call end.
                // Never forward provider bodies/classifier text to the UI.
                val payload = linkedMapOf<String, Any>("type" to VoiceTutorRealtimeContract.INPUT_RETRY_EVENT)
                if (node.has("sequence")) payload["sequence"] = sequence.longValue()
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
            VoiceTutorRealtimeContract.INPUT_SETTLED_EVENT -> {
                val sequence = node.path("sequence")
                if (transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND &&
                    sequence.isIntegralNumber && sequence.canConvertToLong() && sequence.longValue() >= 0
                ) {
                    ProviderEventDecision(mapper.writeValueAsString(mapOf(
                        "type" to type, "sequence" to sequence.longValue(),
                    )))
                } else ProviderEventDecision(payload = null)
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
            VoiceTutorRealtimeContract.QUESTION_CHANGED_EVENT -> {
                val studyId = node.path("studyId")
                val recordId = node.path("recordId")
                if (transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND &&
                    studyId.isIntegralNumber && studyId.canConvertToLong() && studyId.longValue() > 0 &&
                    recordId.isTextual && recordId.asText().matches(Regex("[1-9][0-9]{0,18}")) && recordId.asText().toLongOrNull() != null
                ) ProviderEventDecision(mapper.writeValueAsString(mapOf(
                    "type" to type, "studyId" to studyId.longValue(), "recordId" to recordId.asText(),
                ))) else ProviderEventDecision(payload = null)
            }
            VoiceTutorRealtimeContract.USER_INPUT_REQUEST_EVENT, VoiceTutorRealtimeContract.USER_INPUT_STATE_EVENT -> {
                val sequence = node.path("sequence")
                if (transport != VoiceTutorProviderTransport.WEBRTC_SIDEBAND ||
                    !VoiceTutorUserInputContract.validCorrelation(node) || node.path("sessionId").asText() != sessionId ||
                    !sequence.isIntegralNumber || !sequence.canConvertToLong() || sequence.longValue() <= 0)
                    return ProviderEventDecision(payload = null)
                val payload = linkedMapOf<String, Any>("type" to type, "requestId" to node.path("requestId").asText(),
                    "sessionId" to sessionId, "attemptId" to node.path("attemptId").asText(), "sequence" to sequence.longValue())
                if (type == VoiceTutorRealtimeContract.USER_INPUT_REQUEST_EVENT) {
                    val request = VoiceTutorUserInputContract.request(node) ?: return ProviderEventDecision(payload = null)
                    payload["title"] = request.title
                    payload["questions"] = request.questions
                    node.get("operationId")?.let { id ->
                        if (!validProviderResponseId(id)) return ProviderEventDecision(payload = null)
                        payload["operationId"] = id.asText()
                    }
                } else {
                    val phase = node.path("phase").asText()
                    if (phase !in setOf("pending", "submitted", "cancelled")) return ProviderEventDecision(payload = null)
                    payload["phase"] = phase
                    node.path("errorCode").asText().takeIf { phase == "pending" && it in setOf("INVALID_ANSWERS", "ACTION_FAILED") }
                        ?.let { payload["errorCode"] = it }
                }
                ProviderEventDecision(mapper.writeValueAsString(payload))
            }
            VoiceTutorRealtimeContract.OPERATION_CONTEXT_EVENT -> {
                val id = node.path("operationId")
                val optionalIds = listOf("responseId", "learnerItemId", "tutorItemId")
                if (transport != VoiceTutorProviderTransport.WEBRTC_SIDEBAND || !validProviderResponseId(id) ||
                    optionalIds.any { node.has(it) && !validProviderResponseId(node.path(it)) } ||
                    (node.has("answerId") && !validAnswerId(node.path("answerId")))
                ) return ProviderEventDecision(payload = null)
                val payload = linkedMapOf("type" to type, "operationId" to id.asText())
                optionalIds.forEach { field -> if (node.has(field)) payload[field] = node.path(field).asText() }
                if (node.has("answerId")) payload["answerId"] = node.path("answerId").asText()
                ProviderEventDecision(mapper.writeValueAsString(payload))
            }
            VoiceTutorRealtimeContract.OPERATION_EVENT -> {
                val sequence = node.path("sequence")
                val elapsed = node.path("elapsedMs")
                val id = node.path("operationId")
                val name = node.path("name")
                val phase = node.path("phase").asText()
                if (transport != VoiceTutorProviderTransport.WEBRTC_SIDEBAND ||
                    !sequence.isIntegralNumber || !sequence.canConvertToLong() || sequence.longValue() <= 0 ||
                    !elapsed.isIntegralNumber || !elapsed.canConvertToLong() || elapsed.longValue() !in 0..3_600_000 ||
                    !id.isTextual || !id.asText().matches(Regex("[A-Za-z0-9_-]{1,191}")) ||
                    !name.isTextual || !name.asText().matches(Regex("[a-z][a-z0-9_]{0,63}")) ||
                    phase !in setOf("started", "completed", "failed") || (phase == "started" && elapsed.longValue() != 0L)
                ) return ProviderEventDecision(payload = null)
                ProviderEventDecision(mapper.writeValueAsString(mapOf("type" to type,
                    "operationId" to id.asText(), "name" to name.asText(), "phase" to phase,
                    "elapsedMs" to elapsed.longValue(), "sequence" to sequence.longValue())))
            }
            VoiceTutorRealtimeContract.SESSION_STATE_EVENT -> {
                val sequence = node.path("sequence")
                val revision = node.path("revision")
                val phase = node.path("phase").asText()
                val paused = node.path("paused")
                val study = node.path("studyId")
                val record = node.path("recordId")
                val answer = node.path("answerId")
                val hasStudy = !study.isMissingNode && !study.isNull
                val hasRecord = !record.isMissingNode && !record.isNull
                val hasAnswer = !answer.isMissingNode && !answer.isNull
                if (transport != VoiceTutorProviderTransport.WEBRTC_SIDEBAND ||
                    !sequence.isIntegralNumber || !sequence.canConvertToLong() || sequence.longValue() <= 0 ||
                    !revision.isIntegralNumber || !revision.canConvertToLong() || revision.longValue() < 0 ||
                    phase !in VoiceTutorRealtimeContract.SESSION_PHASES || !paused.isBoolean ||
                    (hasStudy && (!study.isIntegralNumber || !study.canConvertToLong() || study.longValue() <= 0)) ||
                    (hasRecord && (!hasStudy || !validRecordId(record))) ||
                    (hasAnswer && (!hasRecord || !validAnswerId(answer))) ||
                    (phase in VoiceTutorRealtimeContract.ANSWER_SESSION_PHASES && !hasAnswer) ||
                    (phase in VoiceTutorRealtimeContract.RECORD_SESSION_PHASES && !hasRecord)
                ) return ProviderEventDecision(payload = null)
                val payload = linkedMapOf<String, Any>("type" to type, "sequence" to sequence.longValue(),
                    "phase" to phase, "paused" to paused.booleanValue(), "revision" to revision.longValue())
                if (hasStudy) payload["studyId"] = study.longValue()
                if (hasRecord) payload["recordId"] = record.asText()
                if (hasAnswer) payload["answerId"] = answer.asText()
                ProviderEventDecision(mapper.writeValueAsString(payload))
            }
            VoiceTutorRealtimeContract.ANSWER_READY_EVENT, VoiceTutorRealtimeContract.ANSWER_STATE_EVENT,
            VoiceTutorRealtimeContract.ANSWER_TRANSCRIPT_EVENT -> {
                if (transport != VoiceTutorProviderTransport.WEBRTC_SIDEBAND ||
                    !validAnswerId(node.path("answerId")) || !validRecordId(node.path("recordId")) ||
                    !validProviderText(node, "text", 8_000)) return ProviderEventDecision(payload = null)
                val payload = linkedMapOf<String, Any>("type" to type, "answerId" to node.path("answerId").asText(),
                    "recordId" to node.path("recordId").asText(), "text" to node.path("text").asText())
                if (type == VoiceTutorRealtimeContract.ANSWER_TRANSCRIPT_EVENT) {
                    val sequence = node.path("sequence")
                    if (!validProviderResponseId(node.path("itemId")) || !sequence.isIntegralNumber ||
                        !sequence.canConvertToLong() || sequence.longValue() !in 1..32) return ProviderEventDecision(payload = null)
                    payload["itemId"] = node.path("itemId").asText()
                    payload["sequence"] = sequence.longValue()
                } else {
                    val studyId = node.path("studyId")
                    val revision = node.path("revision")
                    val phase = node.path("phase").asText()
                    if (!studyId.isIntegralNumber || !studyId.canConvertToLong() || studyId.longValue() <= 0 ||
                        !revision.isIntegralNumber || !revision.canConvertToLong() || revision.longValue() < 0 ||
                        phase !in ANSWER_PHASES) return ProviderEventDecision(payload = null)
                    if (type == VoiceTutorRealtimeContract.ANSWER_READY_EVENT) {
                        if (phase != "listening" || node.path("text").asText().isNotEmpty() ||
                            !validProviderText(node, "question", 8_000) ||
                            node.path("question").asText().isBlank()) return ProviderEventDecision(payload = null)
                        payload["question"] = node.path("question").asText()
                    }
                    payload["studyId"] = studyId.longValue(); payload["revision"] = revision.longValue(); payload["phase"] = phase
                    node.path("code").asText().takeIf { it in ANSWER_CODES }?.let { payload["code"] = it }
                }
                ProviderEventDecision(mapper.writeValueAsString(payload))
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
            !node.has(VoiceTutorTranscriptMetadata.CONVERSATION_SEQUENCE) &&
            !node.has(VoiceTutorTranscriptMetadata.CANONICAL_ANSWER_SOURCE)
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
        publicNode.remove(VoiceTutorTranscriptMetadata.CANONICAL_ANSWER_SOURCE)
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
            val nativeMarker = node.path(VoiceTutorRealtimeContract.QUOTA_EXHAUSTION_NOTICE_FIELD)
            payload[VoiceTutorRealtimeContract.QUOTA_EXHAUSTION_NOTICE_FIELD] =
                if (transport == VoiceTutorProviderTransport.WEBRTC_SIDEBAND && nativeMarker.isBoolean) {
                    nativeMarker.booleanValue()
                } else marker.isBoolean && marker.booleanValue()
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

    private fun validAnswerId(node: JsonNode): Boolean = node.isTextual && ANSWER_ID_PATTERN.matches(node.asText())
    private fun validRecordId(node: JsonNode): Boolean = node.isTextual &&
        node.asText().matches(Regex("[1-9][0-9]{0,18}")) && node.asText().toLongOrNull() != null

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
        private val ANSWER_ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val ANSWER_CLIENT_EVENTS = setOf(VoiceTutorRealtimeContract.ANSWER_FINISH_EVENT,
            VoiceTutorRealtimeContract.ANSWER_SUBMIT_EVENT, VoiceTutorRealtimeContract.ANSWER_SKIP_EVENT, VoiceTutorRealtimeContract.ANSWER_CANCEL_EVENT)
        private val USER_INPUT_CLIENT_EVENTS = setOf(VoiceTutorRealtimeContract.USER_INPUT_SUBMIT_EVENT,
            VoiceTutorRealtimeContract.USER_INPUT_CANCEL_EVENT)
        private val ANSWER_PHASES = setOf("listening", "finalizing", "review", "submitting", "submitted", "failed", "cancelled")
        private val ANSWER_CODES = setOf("ANSWER_TRANSCRIPT_INCOMPLETE", "ANSWER_SUBMISSION_FAILED", "ANSWER_TOO_LONG", "ANSWER_CANCELLED", "ANSWER_CANCEL_UNAVAILABLE")
        private val ALLOWED_CLIENT_EVENTS = setOf(
            "input_audio_buffer.append",
            "input_audio_buffer.commit",
        )
        private val LOCAL_CLIENT_EVENTS = setOf(
            CLIENT_END_EVENT,
            CLIENT_HEARTBEAT_EVENT,
            VoiceTutorRealtimeContract.PLAYBACK_COMPLETED_EVENT,
            VoiceTutorRealtimeContract.PLAYOUT_DRAINED_EVENT,
            VoiceTutorRealtimeContract.RESPONSE_WORD_FINISHED_EVENT,
            VoiceTutorRealtimeContract.GRADING_REFRESH_EVENT,
            VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT,
            VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT,
            VoiceTutorRealtimeContract.PAUSE_REQUEST_EVENT,
            VoiceTutorRealtimeContract.PAUSE_INPUT_QUIESCED_EVENT,
            VoiceTutorRealtimeContract.RESUME_REQUEST_EVENT,
            VoiceTutorRealtimeContract.ANSWER_FINISH_EVENT,
            VoiceTutorRealtimeContract.ANSWER_SUBMIT_EVENT,
            VoiceTutorRealtimeContract.ANSWER_SKIP_EVENT,
            VoiceTutorRealtimeContract.ANSWER_CANCEL_EVENT,
            VoiceTutorRealtimeContract.USER_INPUT_SUBMIT_EVENT,
            VoiceTutorRealtimeContract.USER_INPUT_CANCEL_EVENT,
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
            VoiceTutorRealtimeContract.ANSWER_FINISH_EVENT,
            VoiceTutorRealtimeContract.ANSWER_SUBMIT_EVENT,
            VoiceTutorRealtimeContract.ANSWER_SKIP_EVENT,
            VoiceTutorRealtimeContract.ANSWER_CANCEL_EVENT,
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
