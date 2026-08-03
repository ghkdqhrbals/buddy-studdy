package com.buddystudy.backend.common.adapter.inbound.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.core.task.TaskRejectedException
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.reactive.resource.NoResourceFoundException
import org.springframework.web.server.MethodNotAllowedException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import org.springframework.web.util.DisconnectedClientHelper
import java.util.UUID

data class ApiErrorEnvelope(val error: ApiError)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val errorCode: String,
    val code: Int,
    val messageKey: String,
    val debugDescription: String,
    val message: String,
    val requestId: String,
    val status: Int,
    val reason: String? = null,
    val requiredPermissions: List<String>? = null,
    val requiredTerms: List<Any>? = null,
    val requiredActions: List<String>? = null,
    val metadata: Map<String, Any?>? = null,
)

@Component
class ApiErrorResponseFactory(
    private val messageSource: MessageSource,
) {
    fun envelope(
        code: ApiErrorCode,
        status: HttpStatus,
        exchange: ServerWebExchange,
        reason: String? = null,
        debugDescription: String = code.debugDescription,
        requiredPermissions: List<String>? = null,
        requiredTerms: List<Any>? = null,
        requiredActions: List<String>? = null,
        metadata: Map<String, Any?>? = null,
    ): ApiErrorEnvelope {
        val requestId = exchange.getAttribute<String>(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE)
            ?: UUID.randomUUID().toString()
        val locale = exchange.localeContext.locale ?: java.util.Locale.getDefault()
        val localizedMessage = messageSource.getMessage(
            code.messageKey,
            null,
            code.debugDescription,
            locale,
        ) ?: code.debugDescription
        return ApiErrorEnvelope(
            ApiError(
                errorCode = code.name,
                code = code.code,
                messageKey = code.messageKey,
                debugDescription = debugDescription,
                message = localizedMessage,
                requestId = requestId,
                status = status.value(),
                reason = reason,
                requiredPermissions = requiredPermissions,
                requiredTerms = requiredTerms,
                requiredActions = requiredActions,
                metadata = metadata,
            )
        )
    }
}

@RestControllerAdvice
class ErrorHandler(
    private val errorResponseFactory: ApiErrorResponseFactory,
    private val loggingPolicy: ApiLoggingPolicy,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiRuntimeException::class)
    fun api(error: ApiRuntimeException, exchange: ServerWebExchange): ResponseEntity<ApiErrorEnvelope> {
        val body = errorResponseFactory.envelope(
            error.errorCode,
            error.status,
            exchange,
            debugDescription = error.message,
            requiredPermissions = error.requiredPermissions,
            requiredTerms = error.requiredTerms,
            requiredActions = error.requiredActions,
            metadata = error.metadata,
        )
        log.warn(
            "api_error {}",
            loggingPolicy.apiError(
                exchange = exchange,
                requestId = body.error.requestId,
                status = error.status,
                code = error.errorCode.name,
                message = error.message,
            ),
        )
        return json(error.status, body)
    }

    @ExceptionHandler(WebExchangeBindException::class)
    fun validation(error: WebExchangeBindException, exchange: ServerWebExchange): ResponseEntity<ApiErrorEnvelope> =
        json(
            HttpStatus.UNPROCESSABLE_ENTITY,
            errorResponseFactory.envelope(ApiErrorCode.VALIDATION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, exchange),
        )

    @ExceptionHandler(ServerWebInputException::class)
    fun invalidInput(error: ServerWebInputException, exchange: ServerWebExchange): ResponseEntity<ApiErrorEnvelope> =
        json(
            HttpStatus.UNPROCESSABLE_ENTITY,
            errorResponseFactory.envelope(ApiErrorCode.VALIDATION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, exchange),
        )

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(error: NoResourceFoundException, exchange: ServerWebExchange): ResponseEntity<ApiErrorEnvelope> =
        json(
            HttpStatus.NOT_FOUND,
            errorResponseFactory.envelope(ApiErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, exchange),
        )

    @ExceptionHandler(MethodNotAllowedException::class)
    fun methodNotAllowed(
        error: MethodNotAllowedException,
        exchange: ServerWebExchange,
    ): ResponseEntity<ApiErrorEnvelope> {
        val status = HttpStatus.METHOD_NOT_ALLOWED
        val body = errorResponseFactory.envelope(ApiErrorCode.METHOD_NOT_ALLOWED, status, exchange)
        return ResponseEntity.status(status)
            .allow(*error.supportedMethods.toTypedArray())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
    }

    @ExceptionHandler(TaskRejectedException::class)
    fun serverBusy(error: TaskRejectedException, exchange: ServerWebExchange): ResponseEntity<ApiErrorEnvelope> {
        val body = errorResponseFactory.envelope(
            ApiErrorCode.SERVER_BUSY,
            HttpStatus.SERVICE_UNAVAILABLE,
            exchange,
        )
        log.warn(
            "api_error {}",
            loggingPolicy.apiError(
                exchange = exchange,
                requestId = body.error.requestId,
                status = HttpStatus.SERVICE_UNAVAILABLE,
                code = ApiErrorCode.SERVER_BUSY.name,
                message = error.message,
            ),
        )
        return json(HttpStatus.SERVICE_UNAVAILABLE, body)
    }

    @ExceptionHandler(Exception::class)
    fun fallback(error: Exception, exchange: ServerWebExchange): ResponseEntity<ApiErrorEnvelope> {
        if (DisconnectedClientHelper.isClientDisconnectedException(error)) {
            throw error
        }
        return internalServerError(error, exchange)
    }

    private fun internalServerError(
        error: Throwable,
        exchange: ServerWebExchange,
    ): ResponseEntity<ApiErrorEnvelope> {
        val details = error.details()
        val body = errorResponseFactory.envelope(
            ApiErrorCode.INTERNAL_SERVER_ERROR,
            HttpStatus.INTERNAL_SERVER_ERROR,
            exchange,
            details.reason,
        )
        val message = loggingPolicy.unexpectedApiError(
            exchange = exchange,
            requestId = body.error.requestId,
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            code = ApiErrorCode.INTERNAL_SERVER_ERROR.name,
            message = body.error.message,
            details = details.toLogDetails(),
        )
        if (loggingPolicy.includesStackTrace) {
            log.error("api_error {}", message, error)
        } else {
            log.error("api_error {}", message)
        }
        return json(HttpStatus.INTERNAL_SERVER_ERROR, body)
    }

    private fun json(status: HttpStatus, body: ApiErrorEnvelope): ResponseEntity<ApiErrorEnvelope> =
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)

    private fun Throwable.details(): ThrowableDetails {
        val rootCause = rootCause()
        val exceptionType = typeName()
        val exceptionMessage = message.toLogValue()
        val rootCauseType = rootCause.typeName()
        val rootCauseMessage = rootCause.message.toLogValue()
        val origin = (rootCause.stackTrace.firstOrNull() ?: stackTrace.firstOrNull())?.toString().toLogValue()
        val primaryReason = reason(exceptionType, exceptionMessage)
        val rootReason = reason(rootCauseType, rootCauseMessage)
        return ThrowableDetails(
            exceptionType = exceptionType,
            exceptionMessage = exceptionMessage,
            rootCauseType = rootCauseType,
            rootCauseMessage = rootCauseMessage,
            origin = origin,
            reason = if (rootCause === this) primaryReason else "$primaryReason; caused by $rootReason",
        )
    }

    private fun Throwable.rootCause(): Throwable {
        var current = this
        repeat(MAX_CAUSE_DEPTH) {
            val next = current.cause ?: return current
            if (next === current) return current
            current = next
        }
        return current
    }

    private fun Throwable.typeName(): String = this::class.simpleName ?: javaClass.simpleName

    private fun String?.toLogValue(): String =
        this
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.take(MAX_DETAIL_LENGTH)
            ?.takeIf(String::isNotBlank)
            ?: "-"

    private fun reason(type: String, detail: String): String =
        if (detail == "-") type else "$type: $detail"

    private data class ThrowableDetails(
        val exceptionType: String,
        val exceptionMessage: String,
        val rootCauseType: String,
        val rootCauseMessage: String,
        val origin: String,
        val reason: String,
    ) {
        fun toLogDetails(): ApiErrorLogDetails =
            ApiErrorLogDetails(
                exceptionType = exceptionType,
                exceptionMessage = exceptionMessage,
                rootCauseType = rootCauseType,
                rootCauseMessage = rootCauseMessage,
                origin = origin,
            )
    }

    private companion object {
        private const val MAX_CAUSE_DEPTH = 32
        private const val MAX_DETAIL_LENGTH = 500
        private val WHITESPACE = Regex("\\s+")
    }
}
