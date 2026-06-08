package com.buddystuddy.backend.common.adapter.inbound.web

import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

data class ApiErrorEnvelope(val error: ApiError)
data class ApiError(val code: String, val message: String, val requestId: String, val status: Int)

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(error: ApiException, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> {
        val body = envelope(error.code, error.message, error.status, request)
        log.warn(
            "api_error requestId={} method={} path={} status={} code={} message={}",
            body.error.requestId,
            request.method,
            request.requestURI,
            error.status.value(),
            error.code.name,
            error.message,
        )
        return ResponseEntity.status(error.status).body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(error: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(envelope(ApiErrorCode.VALIDATION_ERROR, "Invalid request.", HttpStatus.UNPROCESSABLE_ENTITY, request))

    @ExceptionHandler(Exception::class)
    fun fallback(error: Exception, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(envelope(ApiErrorCode.INTERNAL_SERVER_ERROR, "Internal backend error.", HttpStatus.INTERNAL_SERVER_ERROR, request))

    private fun envelope(code: ApiErrorCode, message: String, status: HttpStatus, request: HttpServletRequest): ApiErrorEnvelope {
        val requestId = request.getAttribute("requestId") as? String ?: UUID.randomUUID().toString()
        return ApiErrorEnvelope(ApiError(code.name, message, requestId, status.value()))
    }
}
