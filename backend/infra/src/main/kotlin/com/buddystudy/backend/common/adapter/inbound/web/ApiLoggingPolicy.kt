package com.buddystudy.backend.common.adapter.inbound.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange

@Component
class ApiLoggingPolicy(
    @Value("\${buddystudy.logging.api.detail:detailed}") detail: String,
) {
    private val detail = ApiLogDetail.from(detail)

    val capturesBodies: Boolean
        get() = detail == ApiLogDetail.DETAILED

    val includesRequestIdInMdc: Boolean
        get() = detail == ApiLogDetail.DETAILED

    val includesStackTrace: Boolean
        get() = detail == ApiLogDetail.DETAILED

    fun apiError(
        exchange: ServerWebExchange,
        requestId: String,
        status: HttpStatus,
        code: String,
        message: String?,
    ): String =
        requestFields(exchange.request, requestId) +
            " status=${status.value()} code=$code message=${message.toLogValue()}"

    fun unexpectedApiError(
        exchange: ServerWebExchange,
        requestId: String,
        status: HttpStatus,
        code: String,
        message: String?,
        details: ApiErrorLogDetails,
    ): String {
        val base = apiError(exchange, requestId, status, code, message)
        return if (detail == ApiLogDetail.DETAILED) {
            "$base exceptionType=${details.exceptionType}" +
                " exceptionMessage=${details.exceptionMessage}" +
                " rootCauseType=${details.rootCauseType}" +
                " rootCauseMessage=${details.rootCauseMessage}" +
                " origin=${details.origin}"
        } else {
            "$base cause=${details.rootCauseType}:${details.rootCauseMessage} origin=${details.origin}"
        }
    }

    fun authentication(
        exchange: ServerWebExchange,
        requestId: String,
        status: HttpStatus,
        code: String,
        message: String?,
    ): String =
        requestFields(exchange.request, requestId) +
            " status=${status.value()} code=$code message=${message.toLogValue()}"

    fun loggingFailure(requestId: String, message: String?): String =
        if (detail == ApiLogDetail.DETAILED) {
            "requestId=$requestId message=${message.toLogValue()}"
        } else {
            "message=${message.toLogValue()}"
        }

    private fun requestFields(request: ServerHttpRequest, requestId: String): String {
        val methodAndPath = "method=${request.method} path=${request.path.value()}"
        return if (detail == ApiLogDetail.DETAILED) {
            "requestId=$requestId clientIp=${ClientIpResolver.resolve(request)} $methodAndPath"
        } else {
            methodAndPath
        }
    }

    private fun String?.toLogValue(): String =
        this
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.take(MAX_MESSAGE_LENGTH)
            ?.takeIf(String::isNotBlank)
            ?: "-"

    private enum class ApiLogDetail {
        COMPACT,
        DETAILED;

        companion object {
            fun from(value: String): ApiLogDetail =
                entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: DETAILED
        }
    }

    private companion object {
        private const val MAX_MESSAGE_LENGTH = 500
        private val WHITESPACE = Regex("\\s+")
    }
}

data class ApiErrorLogDetails(
    val exceptionType: String,
    val exceptionMessage: String,
    val rootCauseType: String,
    val rootCauseMessage: String,
    val origin: String,
)
