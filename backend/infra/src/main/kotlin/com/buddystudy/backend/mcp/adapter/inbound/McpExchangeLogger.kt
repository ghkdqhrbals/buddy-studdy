package com.buddystudy.backend.mcp.adapter.inbound

import com.buddystudy.backend.common.application.error.ApiRuntimeException
import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Trusted server context only; never pass a principal, bearer or arbitrary request metadata. */
data class McpExchangeContext(
    val userId: Long?,
    val transport: String,
    val parentRequestId: String? = null,
    val sessionId: String? = null,
    val callId: String? = null,
)

data class McpExchangeResponse(val body: Any?, val isError: Boolean = false)

/** Same Loki/admin shape as REST exchanges, with a separate marker to preserve HTTP metrics. */
@Component
class McpExchangeLogger(private val objectMapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val bodies = McpLogBodySanitizer(objectMapper)

    suspend fun <T> observe(
        context: McpExchangeContext,
        operation: String,
        target: String,
        requestBody: Any?,
        response: (T) -> McpExchangeResponse,
        action: suspend () -> T,
    ): T {
        val observation = Observation(context, operation, target, requestBody)
        try {
            return action().also { observation.capture { response(it) } }
        } catch (error: Throwable) {
            observation.failure = error
            throw error
        } finally {
            observation.finish()
        }
    }

    fun <T : Any> observeMono(
        context: McpExchangeContext,
        operation: String,
        target: String,
        requestBody: Any?,
        response: (T) -> McpExchangeResponse,
        action: () -> Mono<T>,
    ): Mono<T> = Mono.defer {
        val observation = Observation(context, operation, target, requestBody)
        Mono.defer(action)
            .doOnNext { value -> observation.capture { response(value) } }
            .doOnError { observation.failure = it }
            .doFinally { observation.finish(cancelled = it == SignalType.CANCEL) }
    }

    private inner class Observation(
        val context: McpExchangeContext,
        operation: String,
        target: String,
        requestBody: Any?,
    ) {
        val operation = safeOperation(operation)
        val target = if (this.operation == "unknown") "unknown" else target
        val requestId = UUID.randomUUID().toString()
        val startedAt = Instant.now()
        val startedNanos = System.nanoTime()
        val request = bodies.capture(requestBody)
        val finished = AtomicBoolean(false)
        @Volatile var failure: Throwable? = null
        @Volatile var response: McpExchangeResponse? = null

        fun capture(value: () -> McpExchangeResponse) {
            // Observation must never fail or replay an already completed business operation.
            try { response = value() } catch (_: Exception) {
                response = McpExchangeResponse(mapOf("unavailable" to true))
            }
        }

        fun finish(cancelled: Boolean = false) {
            if (!finished.compareAndSet(false, true)) return
            val completedAt = Instant.now()
            val duration = ((System.nanoTime() - startedNanos) / 1_000_000.0).coerceAtLeast(0.0)
            try {
                val error = failure
                val isCancelled = cancelled || error?.let(::isCancellation) == true
                val result = response
                val node = bodies.node(result?.body)
                val resultError = node?.path("error")?.takeIf { it.isObject }
                val failedResult = result?.isError == true || resultError != null
                val status = when {
                    isCancelled -> 499
                    error is ApiRuntimeException -> error.status.value()
                    error is AccessDeniedException -> 403
                    error != null -> 500
                    failedResult -> resultStatus(resultError)
                    result == null -> 500
                    else -> 200
                }
                val code = when {
                    isCancelled -> "CANCELLED"
                    error is ApiRuntimeException -> error.errorCode.name
                    error is AccessDeniedException -> "PERMISSION_DENIED"
                    error != null -> "INTERNAL_SERVER_ERROR"
                    failedResult -> resultError?.path("code")?.asText()?.takeIf(String::isNotBlank) ?: "MCP_ERROR"
                    result == null -> "EMPTY_RESULT"
                    else -> null
                }
                val terminalBody = if (error != null || isCancelled || result == null) {
                    mapOf("error" to mapOf("code" to code, "status" to status))
                } else result.body
                val payload = linkedMapOf<String, Any?>(
                    "requestId" to requestId,
                    "protocol" to "mcp",
                    "transport" to context.transport,
                    "userId" to (context.userId?.toString() ?: "-"),
                    "method" to "MCP",
                    "path" to "/mcp/$operation/${safeIdentifier(target)}",
                    "operation" to operation,
                    "startedAt" to startedAt.toString(),
                    "completedAt" to completedAt.toString(),
                    "durationMs" to "%.2f".format(Locale.US, duration),
                    "query" to "",
                    "requestHeaders" to emptyMap<String, String>(),
                    "requestBody" to request,
                    "status" to status,
                    "responseHeaders" to emptyMap<String, String>(),
                    "responseBody" to bodies.capture(terminalBody),
                )
                if (operation == "tools/call") payload["toolName"] = safeIdentifier(target)
                if (operation == "resources/read") payload["resourceName"] = safeIdentifier(target)
                context.parentRequestId?.let { payload["parentRequestId"] = safeIdentifier(it) }
                context.sessionId?.let { payload["sessionId"] = safeIdentifier(it) }
                context.callId?.let { payload["callId"] = safeIdentifier(it) }
                code?.let { payload["errorCode"] = safeIdentifier(it) }
                error?.let { payload["errorType"] = it.javaClass.name }
                val json = objectMapper.writeValueAsString(payload)
                when {
                    status >= 500 -> log.error("mcp_exchange {}", json)
                    status >= 400 -> log.warn("mcp_exchange {}", json)
                    else -> log.info("mcp_exchange {}", json)
                }
            } catch (error: Exception) {
                // No exception messages or payloads in a logging-failure fallback.
                log.warn("mcp_exchange_logging_failed requestId={} errorType={}", requestId, error.javaClass.name)
            }
        }
    }

    private fun isCancellation(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it is CancellationException || Exceptions.isCancel(it) }

    private fun resultStatus(error: JsonNode?): Int =
        error?.path("status")?.asInt(0)?.takeIf { it in 400..599 } ?: when (error?.path("code")?.asText()) {
            "MCP_UNAVAILABLE", "TOOL_UNAVAILABLE" -> 503
            "INTERNAL_SERVER_ERROR", "INVALID_TOOL_RESULT", "INVALID_QUESTION_RESULT" -> 500
            "CALL_NOT_AUTHORIZED", "STUDY_SCOPE_DENIED", "TOOL_NOT_ALLOWED" -> 403
            else -> 400
        }

    private fun safeIdentifier(value: String) = value.take(160).replace(Regex("[^a-zA-Z0-9_.:-]"), "_")

    private fun safeOperation(value: String) = when (value) {
        "initialize", "ping", "tools/call", "tools/list", "resources/read", "resources/list",
        "resources/templates/list", "resources/subscribe", "resources/unsubscribe", "prompts/get",
        "prompts/list", "logging/setLevel", "completion/complete", "voice/progress" -> value
        else -> "unknown" // The original method remains in the bounded, redacted request body.
    }
}

/** Redact before truncating, including JSON encoded inside MCP text content. */
internal class McpLogBodySanitizer(private val objectMapper: ObjectMapper) {
    private val redactor = SensitiveDataRedactor(objectMapper)

    fun node(value: Any?): JsonNode? = try {
        when (value) {
            null -> null
            is JsonNode -> value
            is String -> if (value.trimStart().startsWith("{") || value.trimStart().startsWith("[")) {
                objectMapper.readTree(value)
            } else TextNode(value)
            else -> objectMapper.valueToTree(value)
        }
    } catch (_: Exception) { null }

    fun capture(value: Any?): Any? = try {
        val source = node(value)
        if (value != null && source == null) mapOf("unavailable" to true)
        else {
            val safe = source?.let { sanitize(it, 0) }
            val text = objectMapper.writeValueAsString(safe)
            if (text.length <= 2_000) safe
            else mapOf("truncated" to true, "observedCharacters" to text.length,
                "preview" to text.take(2_000) + "...[truncated]")
        }
    } catch (_: Exception) { mapOf("unavailable" to true) }

    private fun sanitize(source: JsonNode, depth: Int): JsonNode {
        if (depth > 16) return TextNode("[TRUNCATED_DEPTH]")
        val node = when {
            source.isObject -> objectMapper.createObjectNode().also { target ->
                source.properties().forEach { (key, value) ->
                    val normalized = key.filter(Char::isLetterOrDigit).lowercase()
                    val protected = normalized in setOf("audio", "audiobase64", "pcm", "bytes", "blob", "proposalid", "nonce", "signature", "s3key", "playbackurl", "uploadurl")
                    target.set<JsonNode>(key, if (protected) TextNode("[REDACTED]") else sanitize(value, depth + 1))
                }
            }
            source.isArray -> objectMapper.createArrayNode().also { target ->
                source.forEach { target.add(sanitize(it, depth + 1)) }
            }
            source.isTextual -> {
                val value = source.asText()
                val jsonLike = value.trimStart().startsWith("{") || value.trimStart().startsWith("[")
                val nested = if (jsonLike) node(value) else null
                if (nested != null && !nested.isTextual) TextNode(objectMapper.writeValueAsString(sanitize(nested, depth + 1)))
                else if (jsonLike && nested == null) TextNode("[UNPARSEABLE_JSON]")
                else TextNode(redactor.text(redactor.url(value
                    .replace(Regex("(?i)\\b(Bearer|Basic)\\s+[a-zA-Z0-9._~+/=-]+")) { "${it.groupValues[1]} [REDACTED]" }
                    .replace(Regex("(?i)(https?://)[^/\\s\"<>@]+@")) { "${it.groupValues[1]}[REDACTED]@" }
                    .replace(Regex("\\bsk-(?:proj-|svcacct-)?[a-zA-Z0-9_-]{12,}"), "[REDACTED]")
                    .replace(Regex("\\beyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+"), "[REDACTED]")
                    .replace(Regex("https?://[^\\s\"<>]+")) { match ->
                        if (Regex("(?i)[?&](?:x-amz-[^=]+|x-goog-[^=]+|signature|sig|credential)=").containsMatchIn(match.value)) "[REDACTED_URL]"
                        else match.value.replace(Regex("([?&])([^=&#]+)=([^&#]*)")) { query ->
                            val key = java.net.URLDecoder.decode(query.groupValues[2], Charsets.UTF_8)
                            val signed = key.lowercase().let { it in setOf("signature", "sig", "credential") || it.startsWith("x-amz-") || it.startsWith("x-goog-") }
                            "${query.groupValues[1]}${query.groupValues[2]}=" + if (signed) "[REDACTED]"
                                else redactor.fields(mapOf(key to query.groupValues[3])).getValue(key)
                        }
                    })))
            }
            else -> source.deepCopy()
        }
        return if (node.isObject || node.isArray) objectMapper.readTree(redactor.json(objectMapper.writeValueAsString(node))) else node
    }
}
