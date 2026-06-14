package com.buddystuddy.backend.common.adapter.inbound.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

internal class ApiExchangeLogFormatter(
    private val objectMapper: ObjectMapper,
) {
    fun apiExchangeJson(
        requestId: String,
        request: ContentCachingRequestWrapper,
        response: ContentCachingResponseWrapper,
        durationMs: Double,
    ): String =
        buildJson(
            "requestId" to requestId,
            "clientIp" to ClientIpResolver.resolve(request),
            "request" to requestFields(request),
            "response" to responseFields(response, durationMs),
        )

    fun apiResponseJson(
        requestId: String,
        request: ContentCachingRequestWrapper,
        response: ContentCachingResponseWrapper,
        durationMs: Double,
        includeBody: Boolean = true,
    ): String =
        buildJson(
            "requestId" to requestId,
            "clientIp" to ClientIpResolver.resolve(request),
            "method" to request.method,
            "path" to request.requestURI,
            "response" to responseFields(response, durationMs, includeBody),
        )

    private fun requestFields(request: ContentCachingRequestWrapper): Map<String, Any?> =
        mapOf(
            "method" to request.method,
            "path" to request.requestURI,
            "query" to (request.queryString ?: ""),
            "headers" to headers(request),
            "body" to body(request.contentAsByteArray, request.characterEncoding, request.contentType),
        )

    private fun responseFields(
        response: ContentCachingResponseWrapper,
        durationMs: Double,
        includeBody: Boolean = true,
    ): Map<String, Any?> =
        mapOf(
            "status" to response.status,
            "durationMs" to "%.2f".format(Locale.US, durationMs),
            "headers" to responseHeaders(response),
            "body" to if (includeBody) body(response.contentAsByteArray, response.characterEncoding, response.contentType) else "",
        )

    private fun headers(request: HttpServletRequest): Map<String, String> =
        request.headerNames.asSequence().associateWith { name ->
            if (isSensitiveHeader(name)) "[REDACTED]" else request.getHeaders(name).asSequence().joinToString(",")
        }

    private fun responseHeaders(response: HttpServletResponse): Map<String, String> =
        response.headerNames.associateWith { name ->
            if (isSensitiveHeader(name)) "[REDACTED]" else response.getHeaders(name).joinToString(",")
        }

    private fun isSensitiveHeader(name: String): Boolean =
        name.trim().lowercase(Locale.US) in SENSITIVE_HEADERS

    private fun body(bytes: ByteArray, encoding: String?, contentType: String?): Any? {
        if (bytes.isEmpty()) return ""
        val charset = charsetFor(encoding, contentType)
        val body = redact(String(bytes, charset)).let {
            if (it.length > MAX_BODY_CHARS) it.take(MAX_BODY_CHARS) + "...[truncated]" else it
        }
        return parseJsonBody(body, contentType)
    }

    private fun parseJsonBody(body: String, contentType: String?): Any? {
        val trimmed = body.trim()
        val jsonContentType = contentType?.contains("json", ignoreCase = true) == true
        val jsonLikeBody = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        if (!jsonContentType && !jsonLikeBody) return body
        return runCatching { objectMapper.readTree(body) }.getOrElse { body }
    }

    private fun charsetFor(encoding: String?, contentType: String?): Charset {
        contentType
            ?.split(";")
            ?.asSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { charsetName ->
                runCatching { Charset.forName(charsetName) }.getOrNull()
            }
            ?.let { return it }

        if (contentType?.contains("json", ignoreCase = true) == true) {
            return StandardCharsets.UTF_8
        }

        return encoding?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: StandardCharsets.UTF_8
    }

    private fun redact(value: String): String =
        value.replace(
            Regex("(?i)(\"(?:openaiApiKey|apiKey|idToken|accessToken|refreshToken|clientSecret|password|verificationCode)\"\\s*:\\s*)\"[^\"]*\""),
        ) {
            "${it.groupValues[1]}\"[REDACTED]\""
        }

    private fun buildJson(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":${jsonValue(value)}"
        }

    private fun jsonValue(value: Any?): String =
        when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            is JsonNode -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { entry ->
                "\"${escape(entry.key.toString())}\":${jsonValue(entry.value)}"
            }
            else -> "\"${escape(value.toString())}\""
        }

    private fun escape(value: String): String =
        value.flatMap {
            when (it) {
                '\\' -> listOf('\\', '\\')
                '"' -> listOf('\\', '"')
                '\n' -> listOf('\\', 'n')
                '\r' -> listOf('\\', 'r')
                '\t' -> listOf('\\', 't')
                else -> listOf(it)
            }
        }.joinToString("")

    private companion object {
        private const val MAX_BODY_CHARS = 2_000
        private val SENSITIVE_HEADERS = setOf("cookie", "set-cookie", "x-client-secret")
    }
}
