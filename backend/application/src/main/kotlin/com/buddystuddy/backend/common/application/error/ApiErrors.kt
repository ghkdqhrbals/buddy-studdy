package com.buddystuddy.backend.common.application.error

import org.springframework.http.HttpStatus

enum class ApiErrorCode {
    AUTH_ACCESS_TOKEN_REQUIRED,
    AUTH_DEVICE_CREDENTIALS_REQUIRED,
    AUTH_DEVICE_MISMATCH,
    AUTH_EMAIL_VERIFICATION_REQUIRED,
    AUTH_GOOGLE_REQUIRED,
    AUTH_INVALID_ACCESS_TOKEN,
    AUTH_INVALID_DEVICE_CREDENTIALS,
    DEVICE_NOT_FOUND,
    EMAIL_DELIVERY_FAILED,
    OPENAI_API_KEY_INVALID,
    OPENAI_API_KEY_MISSING,
    ACCOUNT_FORBIDDEN,
    PERMISSION_DENIED,
    RECORD_NOT_FOUND,
    RESOURCE_NOT_FOUND,
    STUDY_SETTINGS_MISSING,
    VALIDATION_ERROR,
    INTERNAL_SERVER_ERROR,
}

class ApiException(
    val status: HttpStatus,
    val code: ApiErrorCode,
    override val message: String,
    val requiredPermissions: List<String>? = null,
    val loginRequired: Boolean? = null,
) : RuntimeException(message)
