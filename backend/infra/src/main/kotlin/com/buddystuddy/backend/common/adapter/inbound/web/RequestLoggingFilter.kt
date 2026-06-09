package com.buddystuddy.backend.common.adapter.inbound.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

@Component
class RequestLoggingFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val requestId = UUID.randomUUID().toString()
        val requestWrapper = ContentCachingRequestWrapper(request, MAX_BODY_BYTES)
        val responseWrapper = ContentCachingResponseWrapper(response)
        requestWrapper.setAttribute("requestId", requestId)
        val started = System.nanoTime()
        try {
            filterChain.doFilter(requestWrapper, responseWrapper)
        } finally {
            val durationMs = (System.nanoTime() - started) / 1_000_000.0
            if (requestWrapper.requestURI.startsWith("/api/")) {
                log.info(
                    "api_exchange {}",
                    apiExchangeJson(requestId, requestWrapper, responseWrapper, durationMs),
                )
            } else {
                log.info(
                    "api_response {}",
                    apiResponseJson(requestId, requestWrapper, responseWrapper, durationMs, includeBody = false),
                )
            }
            responseWrapper.copyBodyToResponse()
        }
    }

    private fun apiExchangeJson(
        requestId: String,
        request: ContentCachingRequestWrapper,
        response: ContentCachingResponseWrapper,
        durationMs: Double,
    ): String =
        buildJson(
            "requestId" to requestId,
            "request" to requestFields(request),
            "response" to responseFields(response, durationMs),
        )

    private fun requestFields(request: ContentCachingRequestWrapper): Map<String, Any> =
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
    ): Map<String, Any> =
        mapOf(
            "status" to response.status,
            "durationMs" to "%.2f".format(Locale.US, durationMs),
            "headers" to responseHeaders(response),
            "body" to if (includeBody) body(response.contentAsByteArray, response.characterEncoding, response.contentType) else "",
        )

    private fun apiResponseJson(
        requestId: String,
        request: ContentCachingRequestWrapper,
        response: ContentCachingResponseWrapper,
        durationMs: Double,
        includeBody: Boolean = true,
    ): String =
        buildJson(
            "requestId" to requestId,
            "method" to request.method,
            "path" to request.requestURI,
            "response" to responseFields(response, durationMs, includeBody),
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

    private fun body(bytes: ByteArray, encoding: String?, contentType: String?): String {
        if (bytes.isEmpty()) return ""
        val charset = charsetFor(encoding, contentType)
        return redact(String(bytes, charset)).let {
            if (it.length > MAX_BODY_CHARS) it.take(MAX_BODY_CHARS) + "...[truncated]" else it
        }
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
        value
            .replace(Regex("(?i)(\"(?:openaiApiKey|apiKey|idToken|accessToken|refreshToken|clientSecret|password|verificationCode)\"\\s*:\\s*)\"[^\"]*\"")) {
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

    companion object {
        private const val MAX_BODY_CHARS = 2_000
        private const val MAX_BODY_BYTES = 8_192
        private val SENSITIVE_HEADERS = setOf("x-client-secret")
    }
}
