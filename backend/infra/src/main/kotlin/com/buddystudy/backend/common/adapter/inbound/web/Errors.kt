package com.buddystudy.backend.common.adapter.inbound.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
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
    val showPopup: Boolean,
    val reason: String? = null,
    val requiredPermissions: List<String>? = null,
    val loginRequired: Boolean? = null,
)

@RestControllerAdvice
class ErrorHandler(
    private val messageSource: MessageSource,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiRuntimeException::class)
    fun api(error: ApiRuntimeException, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> {
        val body = envelope(
            error.errorCode,
            error.status,
            request,
            debugDescription = error.message,
            requiredPermissions = error.requiredPermissions,
            loginRequired = error.loginRequired,
        )
        log.warn(
            "api_error requestId={} clientIp={} method={} path={} status={} code={} message={}",
            body.error.requestId,
            ClientIpResolver.resolve(request),
            request.method,
            request.requestURI,
            error.status.value(),
            error.errorCode.name,
            error.message,
        )
        return json(error.status, body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(error: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> =
        json(
            HttpStatus.UNPROCESSABLE_ENTITY,
            envelope(ApiErrorCode.VALIDATION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, request),
        )

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun notFound(error: Exception, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> =
        json(
            HttpStatus.NOT_FOUND,
            envelope(ApiErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, request),
        )

    @ExceptionHandler(Exception::class)
    fun fallback(error: Exception, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> {
        val body = envelope(
            ApiErrorCode.INTERNAL_SERVER_ERROR,
            HttpStatus.INTERNAL_SERVER_ERROR,
            request,
            error.toReason(),
        )
        log.error(
            "api_error requestId={} clientIp={} method={} path={} status={} code={} message={}",
            body.error.requestId,
            ClientIpResolver.resolve(request),
            request.method,
            request.requestURI,
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

    private fun envelope(
        code: ApiErrorCode,
        status: HttpStatus,
        request: HttpServletRequest,
        reason: String? = null,
        debugDescription: String = code.debugDescription,
        requiredPermissions: List<String>? = null,
        loginRequired: Boolean? = null,
    ): ApiErrorEnvelope {
        val requestId = request.getAttribute("requestId") as? String ?: UUID.randomUUID().toString()
        val localizedMessage = messageSource.getMessage(
            code.messageKey,
            null,
            code.debugDescription,
            LocaleContextHolder.getLocale(),
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
                showPopup = code.showPopup,
                reason = reason,
                requiredPermissions = requiredPermissions,
                loginRequired = loginRequired,
            )
        )
    }

    private fun Exception.toReason(): String {
        val type = this::class.simpleName ?: javaClass.simpleName
        val detail = message?.takeIf { it.isNotBlank() }
        return if (detail == null) type else "$type: $detail"
    }
}
