package com.buddystudy.backend.common.application.error

import org.springframework.http.HttpStatus

enum class ApiErrorCode(
    private val description: String,
    private val code: Int,
    val showPopup: Boolean = true,
) {
    AUTH_ACCESS_TOKEN_REQUIRED("Access token is required.", 100, showPopup = false),
    AUTH_DEVICE_CREDENTIALS_REQUIRED("Device credentials are required.", 101, showPopup = false),
    AUTH_DEVICE_MISMATCH("Access token device does not match.", 102, showPopup = false),
    AUTH_EMAIL_VERIFICATION_REQUIRED("Email verification is required.", 103),
    AUTH_GOOGLE_REQUIRED("Google login is required.", 104),
    AUTH_INVALID_ACCESS_TOKEN("Access token is invalid.", 105, showPopup = false),
    AUTH_INVALID_DEVICE_CREDENTIALS("Device credentials are invalid.", 106, showPopup = false),
    DEVICE_NOT_FOUND("Device registration was not found.", 107, showPopup = false),

    OPENAI_API_KEY_MISSING("OpenAI API key is missing.", 200),
    STUDY_SETTINGS_MISSING("Study settings are missing.", 201),
    OPENAI_API_KEY_INVALID("OpenAI API key is invalid.", 202),

    ACCOUNT_FORBIDDEN("Account access is forbidden.", 300),
    PERMISSION_DENIED("Permission is denied.", 301),

    RECORD_NOT_FOUND("Record was not found.", 400),
    RESOURCE_NOT_FOUND("Resource was not found.", 401),

    VALIDATION_ERROR("Request validation failed.", 500),

    INTERNAL_SERVER_ERROR("Internal server error.", 900),
    EMAIL_DELIVERY_FAILED("Email delivery failed.", 901),
    ;

    fun code(): Int = code

    fun description(): String = description
}

class ApiException(
    val status: HttpStatus,
    val code: ApiErrorCode,
    override val message: String,
    val requiredPermissions: List<String>? = null,
    val loginRequired: Boolean? = null,
) : RuntimeException(message)
