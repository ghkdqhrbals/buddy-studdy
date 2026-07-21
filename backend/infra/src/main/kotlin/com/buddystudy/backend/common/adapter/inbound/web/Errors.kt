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
import org.springframework.web.server.ServerWebExchange
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
            "api_error requestId={} clientIp={} method={} path={} status={} code={} message={}",
            body.error.requestId,
            ClientIpResolver.resolve(exchange.request),
            exchange.request.method,
            exchange.request.path.value(),
            error.status.value(),
            error.errorCode.name,
            error.message,
        )
        return json(error.status, body)
    }

    @ExceptionHandler(WebExchangeBindException::class)
    fun validation(error: WebExchangeBindException, exchange: ServerWebExchange): ResponseEntity<ApiErrorEnvelope> =
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

    @ExceptionHandler(TaskRejectedException::class)
    fun serverBusy(error: TaskRejectedException, exchange: ServerWebExchange): ResponseEntity<ApiErrorEnvelope> {
        val body = errorResponseFactory.envelope(
            ApiErrorCode.SERVER_BUSY,
            HttpStatus.SERVICE_UNAVAILABLE,
            exchange,
        )
        log.warn(
            "api_error requestId={} clientIp={} method={} path={} status={} code={} message={}",
            body.error.requestId,
            ClientIpResolver.resolve(exchange.request),
            exchange.request.method,
            exchange.request.path.value(),
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            ApiErrorCode.SERVER_BUSY.name,
            error.message,
        )
        return json(HttpStatus.SERVICE_UNAVAILABLE, body)
    }

    @ExceptionHandler(Exception::class)
    fun fallback(error: Exception, exchange: ServerWebExchange): ResponseEntity<ApiErrorEnvelope> {
        val body = errorResponseFactory.envelope(
            ApiErrorCode.INTERNAL_SERVER_ERROR,
            HttpStatus.INTERNAL_SERVER_ERROR,
            exchange,
            error.toReason(),
        )
        log.error(
            "api_error requestId={} clientIp={} method={} path={} status={} code={} message={}",
            body.error.requestId,
            ClientIpResolver.resolve(exchange.request),
            exchange.request.method,
            exchange.request.path.value(),
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            ApiErrorCode.INTERNAL_SERVER_ERROR.name,
            body.error.message,
            error,
        )
        return json(HttpStatus.INTERNAL_SERVER_ERROR, body)
    }

    private fun json(status: HttpStatus, body: ApiErrorEnvelope): ResponseEntity<ApiErrorEnvelope> =
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)

    private fun Exception.toReason(): String {
        val type = this::class.simpleName ?: javaClass.simpleName
        val detail = message?.takeIf { it.isNotBlank() }
        return if (detail == null) type else "$type: $detail"
    }
}
