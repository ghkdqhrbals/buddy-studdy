package com.buddystudy.backend.voice.adapter.outbound.openai

import com.fasterxml.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

internal enum class VoiceTutorProviderErrorDisposition {
    RECOVERABLE,
    SESSION_FATAL,
    PROTOCOL_INVALID,
}

internal enum class VoiceTutorProviderTurnFailureKind(val diagnosticValue: String) {
    PROVIDER_ERROR("provider-error"),
    RESPONSE_TIMEOUT("response-timeout"),
    RESPONSE_CANCELLED("response-cancelled"),
    RESPONSE_INCOMPLETE("response-incomplete"),
    RESPONSE_FAILED("response-failed"),
    OUTPUT_BUFFER_CLEARED("output-buffer-cleared"),
}

internal enum class VoiceTutorProviderEventCorrelation(val diagnosticValue: String) {
    ACTIVE_RESPONSE("active-response"),
    STALE_RESPONSE("stale-response"),
    INTERNAL_CONTROL("internal-control"),
    EXTERNAL_EVENT("external-event"),
    MISSING("missing"),
}

internal enum class VoiceTutorProviderTurnFailureAction(val diagnosticValue: String) {
    RETRY_SCHEDULED("retry"),
    TURN_ABANDONED("turn-abandoned"),
    SERVER_CALL_REJECTED("server-call-rejected"),
    IGNORED("ignored"),
    SESSION_FATAL("session-fatal"),
}

/** Safe, bounded diagnostics only. Provider messages and raw payloads never enter this value. */
internal data class VoiceTutorProviderTurnFailureDiagnostic(
    val kind: VoiceTutorProviderTurnFailureKind,
    val providerErrorType: String,
    val providerErrorCode: String,
    val eventCorrelation: VoiceTutorProviderEventCorrelation,
    val causedEventRef: String,
    val attempt: Int,
    val action: VoiceTutorProviderTurnFailureAction,
)

/**
 * Sanitized proof that the provider rejected one conversation item-create envelope. Raw provider
 * messages and item contents are deliberately absent; the event id still requires exact
 * correlation against coordinator-owned state before the rejection is actionable.
 */
internal data class VoiceTutorProviderItemCreateRejection(
    val eventId: String,
    val errorCode: String,
    val parameter: String,
)

internal fun classifyRealtimeProviderError(node: JsonNode): VoiceTutorProviderErrorDisposition {
    if (!node.isObject || node.path("type").asText() != "error") {
        return VoiceTutorProviderErrorDisposition.PROTOCOL_INVALID
    }
    val error = node.path("error")
    if (!error.isObject || !validRequiredProviderToken(error.get("type")) ||
        !validOptionalProviderToken(error.get("code")) || !validOptionalProviderEventId(error.get("event_id"))
    ) {
        return VoiceTutorProviderErrorDisposition.PROTOCOL_INVALID
    }
    val type = safeProviderToken(error.get("type"))
    val code = safeProviderToken(error.get("code"))
    return if (type in SESSION_FATAL_ERROR_TYPES || code in SESSION_FATAL_ERROR_CODES) {
        VoiceTutorProviderErrorDisposition.SESSION_FATAL
    } else {
        VoiceTutorProviderErrorDisposition.RECOVERABLE
    }
}

internal fun safeProviderErrorType(node: JsonNode): String = safeProviderToken(node.path("error").get("type"))

internal fun safeProviderErrorCode(node: JsonNode): String = safeProviderToken(node.path("error").get("code"))

internal fun safeProviderCausedEventId(node: JsonNode): String? = node.path("error").get("event_id")
    ?.takeIf { it.isTextual && PROVIDER_EVENT_ID.matches(it.textValue()) }
    ?.textValue()

internal fun isSafeRealtimeInternalControlError(node: JsonNode): Boolean {
    val eventId = safeProviderCausedEventId(node) ?: return false
    return eventId.startsWith("buddystudy-internal-drain-") ||
        eventId.startsWith("buddystudy-internal-relay-terminal-")
}

/**
 * Accept only structured validation failures tied to fields owned by the synthetic function-call
 * envelope. Exact event ownership is intentionally not inferred here; the MCP coordinator must
 * still match [VoiceTutorProviderItemCreateRejection.eventId] byte-for-byte before dropping work.
 */
internal fun safeRealtimeItemCreateRejection(node: JsonNode): VoiceTutorProviderItemCreateRejection? {
    if (classifyRealtimeProviderError(node) != VoiceTutorProviderErrorDisposition.RECOVERABLE) return null
    val error = node.path("error")
    if (safeProviderErrorType(node) != "invalid_request_error") return null
    val code = safeProviderErrorCode(node).takeIf { it in SAFE_ITEM_CREATE_REJECTION_CODES } ?: return null
    val parameter = error.get("param")
        ?.takeIf { it.isTextual && it.textValue() in SAFE_ITEM_CREATE_REJECTION_PARAMETERS }
        ?.textValue()
        ?: return null
    val eventId = safeProviderCausedEventId(node) ?: return null
    return VoiceTutorProviderItemCreateRejection(eventId, code, parameter)
}

/**
 * Provider-capacity failures can be emitted without an event id while the
 * Realtime session remains usable. They are safe to abandon, never to replay.
 */
internal fun isSafeUncorrelatedRealtimeProviderError(node: JsonNode): Boolean {
    val type = safeProviderErrorType(node)
    val code = safeProviderErrorCode(node)
    return type in SAFE_UNCORRELATED_ERROR_TYPES || code in SAFE_UNCORRELATED_ERROR_CODES
}

internal fun providerEventReference(eventId: String?): String = eventId?.let {
    HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray(StandardCharsets.UTF_8)),
    ).take(16)
} ?: "none"

private fun validOptionalProviderToken(node: JsonNode?): Boolean =
    node == null || node.isNull || (node.isTextual && PROVIDER_TOKEN.matches(node.textValue()))

private fun validRequiredProviderToken(node: JsonNode?): Boolean =
    node != null && node.isTextual && PROVIDER_TOKEN.matches(node.textValue())

private fun validOptionalProviderEventId(node: JsonNode?): Boolean =
    node == null || node.isNull || (node.isTextual && PROVIDER_EVENT_ID.matches(node.textValue()))

private fun safeProviderToken(node: JsonNode?): String = node
    ?.takeIf { it.isTextual && PROVIDER_TOKEN.matches(it.textValue()) }
    ?.textValue()
    ?: "none"

private val PROVIDER_TOKEN = Regex("[A-Za-z0-9_.-]{1,64}")
private val PROVIDER_EVENT_ID = Regex("[A-Za-z0-9_.:-]{1,191}")

private val SAFE_ITEM_CREATE_REJECTION_CODES = setOf(
    "invalid_type",
    "invalid_value",
    "missing_required_parameter",
    "string_above_max_length",
    "string_below_min_length",
    "unknown_parameter",
)
private val SAFE_ITEM_CREATE_REJECTION_PARAMETERS = setOf(
    "item",
    "item.arguments",
    "item.call_id",
    "item.id",
    "item.name",
    "item.status",
    "item.type",
)

// Only errors that make the Realtime session itself unusable belong here.
// Response/request failures and provider server errors remain turn-local.
private val SESSION_FATAL_ERROR_TYPES = setOf(
    "authentication_error",
    "session_closed",
    "session_expired",
)
private val SESSION_FATAL_ERROR_CODES = setOf(
    "authentication_failed",
    "invalid_api_key",
    "invalid_session",
    "session_closed",
    "session_expired",
    "session_not_found",
)
private val SAFE_UNCORRELATED_ERROR_TYPES = setOf(
    "server_error",
)
private val SAFE_UNCORRELATED_ERROR_CODES = setOf(
    "rate_limit_exceeded",
    "server_error",
    "service_unavailable",
    "temporarily_unavailable",
)
