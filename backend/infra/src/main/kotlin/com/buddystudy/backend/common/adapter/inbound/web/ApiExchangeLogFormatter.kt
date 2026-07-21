package com.buddystudy.backend.common.adapter.inbound.web

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class CapturedBody(
    val bytes: ByteArray,
    val observedBytes: Long,
    val truncated: Boolean,
) {
    companion object {
        val EMPTY = CapturedBody(ByteArray(0), 0, false)
    }
}

internal class ApiExchangeLogFormatter(
    private val objectMapper: ObjectMapper,
) {
    fun apiExchangeJson(
        requestId: String,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        requestBody: CapturedBody,
        responseBody: CapturedBody,
        durationMs: Double,
    ): String =
        buildJson(
            "requestId" to requestId,
            "clientIp" to ClientIpResolver.resolve(request),
            "method" to request.method.name(),
            "path" to request.path.value(),
            "query" to (request.uri.rawQuery ?: ""),
            "requestHeaders" to headers(request.headers),
            "requestBody" to body(requestBody, request.headers),
            "status" to (response.statusCode?.value() ?: 200),
            "durationMs" to "%.2f".format(Locale.US, durationMs),
            "responseHeaders" to headers(response.headers),
            "responseBody" to body(responseBody, response.headers),
        )

    fun apiResponseJson(
        requestId: String,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        responseBody: CapturedBody,
        durationMs: Double,
        includeBody: Boolean = true,
    ): String =
        buildJson(
            "requestId" to requestId,
            "clientIp" to ClientIpResolver.resolve(request),
            "method" to request.method.name(),
            "path" to request.path.value(),
            "response" to mapOf(
                "status" to (response.statusCode?.value() ?: 200),
                "durationMs" to "%.2f".format(Locale.US, durationMs),
                "headers" to headers(response.headers),
                "body" to if (includeBody) body(responseBody, response.headers) else "",
            ),
        )

    private fun headers(headers: HttpHeaders): Map<String, Any?> =
        headers.headerNames().associateWith { name ->
            if (isSensitiveHeader(name)) {
                "[REDACTED]"
            } else {
                headerValue(headers[name] ?: emptyList())
            }
        }

    private fun isSensitiveHeader(name: String): Boolean =
        name.trim().lowercase(Locale.US) in SENSITIVE_HEADERS

    private fun body(captured: CapturedBody, headers: HttpHeaders): Any? {
        val bytes = captured.bytes
        if (bytes.isEmpty()) return ""
        val charset = charsetFor(headers)
        val value = redact(String(bytes, charset))
        if (captured.truncated || value.length > MAX_BODY_CHARS) {
            return mapOf(
                "truncated" to true,
                "observedBytes" to captured.observedBytes,
                "preview" to value.take(MAX_BODY_CHARS) + "...[truncated]",
            )
        }
        return parseJsonBody(value, headers.contentType?.toString())
    }

    private fun parseJsonBody(body: String, contentType: String?): Any? {
        val trimmed = body.trim()
        val jsonContentType = contentType?.contains("json", ignoreCase = true) == true
        val jsonLikeBody = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        if (!jsonContentType && !jsonLikeBody) return body
        return runCatching { objectMapper.readTree(body) }.getOrElse { body }
    }

    private fun headerValue(values: List<String>): Any? {
        if (values.isEmpty()) return ""
        if (values.size > 1) return values.map { parseJsonLikeString(it) }
        return parseJsonLikeString(values.single())
    }

    private fun parseJsonLikeString(value: String): Any? {
        val trimmed = value.trim()
        val jsonLike = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        if (!jsonLike) return value
        return runCatching { objectMapper.readTree(trimmed) }.getOrElse { value }
    }

    private fun charsetFor(headers: HttpHeaders) =
        headers.contentType?.charset ?: StandardCharsets.UTF_8

    private fun redact(value: String): String =
        value.replace(
            Regex("(?i)(\"(?:openaiApiKey|apiKey|idToken|accessToken|refreshToken|clientSecret|password|verificationCode)\"\\s*:\\s*)\"[^\"]*\""),
        ) {
            "${it.groupValues[1]}\"[REDACTED]\""
        }

    private fun buildJson(vararg fields: Pair<String, Any?>): String =
        objectMapper.writeValueAsString(linkedMapOf(*fields))

    private companion object {
        private const val MAX_BODY_CHARS = 2_000
        private val SENSITIVE_HEADERS = setOf(
            "authorization",
            "cookie",
            "set-cookie",
            "x-client-secret",
        )
    }
}
