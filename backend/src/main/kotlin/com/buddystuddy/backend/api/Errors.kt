package com.buddystuddy.backend.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

enum class ApiErrorCode {
    AUTH_ACCESS_TOKEN_REQUIRED,
    AUTH_DEVICE_MISMATCH,
    AUTH_GOOGLE_REQUIRED,
    AUTH_INVALID_ACCESS_TOKEN,
    AUTH_INVALID_DEVICE_CREDENTIALS,
    DEVICE_NOT_FOUND,
    OPENAI_API_KEY_INVALID,
    OPENAI_API_KEY_MISSING,
    RECORD_NOT_FOUND,
    STUDY_SETTINGS_MISSING,
    VALIDATION_ERROR,
    INTERNAL_SERVER_ERROR,
}

class ApiException(
    val status: HttpStatus,
    val code: ApiErrorCode,
    override val message: String,
) : RuntimeException(message)

data class ApiErrorEnvelope(val error: ApiError)
data class ApiError(val code: String, val message: String, val requestId: String, val status: Int)

@RestControllerAdvice
class ErrorHandler {
    @ExceptionHandler(ApiException::class)
    fun api(error: ApiException, request: HttpServletRequest): ResponseEntity<ApiErrorEnvelope> =
        ResponseEntity.status(error.status).body(envelope(error.code, error.message, error.status, request))

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
