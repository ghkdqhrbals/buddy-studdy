package com.buddystuddy.backend.common.adapter.inbound.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
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
    val code: String,
    val message: String,
    val requestId: String,
    val status: Int,
    val reason: String? = null,
    val requiredPermissions: List<String>? = null,
    val loginRequired: Boolean? = null,
)

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(error: ApiException, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> {
        val body = envelope(
            error.code,
            error.message,
            error.status,
            request,
            requiredPermissions = error.requiredPermissions,
            loginRequired = error.loginRequired,
        )
        log.warn(
            "api_error requestId={} method={} path={} status={} code={} message={}",
            body.error.requestId,
            request.method,
            request.requestURI,
            error.status.value(),
            error.code.name,
            error.message,
        )
        return json(error.status, body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(error: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> =
        json(
            HttpStatus.UNPROCESSABLE_ENTITY,
            envelope(ApiErrorCode.VALIDATION_ERROR, "Invalid request.", HttpStatus.UNPROCESSABLE_ENTITY, request),
        )

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun notFound(error: Exception, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> =
        json(
            HttpStatus.NOT_FOUND,
            envelope(ApiErrorCode.RESOURCE_NOT_FOUND, "Resource not found.", HttpStatus.NOT_FOUND, request),
        )

    @ExceptionHandler(Exception::class)
    fun fallback(error: Exception, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> =
        json(
            HttpStatus.INTERNAL_SERVER_ERROR,
            envelope(ApiErrorCode.INTERNAL_SERVER_ERROR, "Internal backend error.", HttpStatus.INTERNAL_SERVER_ERROR, request, error.toReason()),
        )

    private fun json(status: HttpStatus, body: ApiErrorEnvelope): ResponseEntity<ApiErrorEnvelope> =
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)

    private fun envelope(
        code: ApiErrorCode,
        message: String,
        status: HttpStatus,
        request: HttpServletRequest,
        reason: String? = null,
        requiredPermissions: List<String>? = null,
        loginRequired: Boolean? = null,
    ): ApiErrorEnvelope {
        val requestId = request.getAttribute("requestId") as? String ?: UUID.randomUUID().toString()
        return ApiErrorEnvelope(ApiError(code.name, message, requestId, status.value(), reason, requiredPermissions, loginRequired))
    }

    private fun Exception.toReason(): String {
        val type = this::class.simpleName ?: javaClass.simpleName
        val detail = message?.takeIf { it.isNotBlank() }
        return if (detail == null) type else "$type: $detail"
    }
}
