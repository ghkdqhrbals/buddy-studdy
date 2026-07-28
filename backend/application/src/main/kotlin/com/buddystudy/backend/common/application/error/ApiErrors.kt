package com.buddystudy.backend.common.application.error

import org.springframework.http.HttpStatus

enum class ApiErrorCode(
    val status: HttpStatus,
    val code: Int,
    val messageKey: String,
    val debugDescription: String,
) {
    AUTH_ACCESS_TOKEN_REQUIRED(HttpStatus.UNAUTHORIZED, 100, "error.auth.access_token_required", "Access token is required."),
    AUTH_DEVICE_CREDENTIALS_REQUIRED(HttpStatus.UNAUTHORIZED, 101, "error.auth.device_credentials_required", "Device credentials are required."),
    AUTH_DEVICE_MISMATCH(HttpStatus.UNAUTHORIZED, 102, "error.auth.device_mismatch", "Access token device does not match."),
    AUTH_EMAIL_VERIFICATION_REQUIRED(HttpStatus.FORBIDDEN, 103, "error.auth.email_verification_required", "Email verification is required."),
    AUTH_GOOGLE_REQUIRED(HttpStatus.UNAUTHORIZED, 104, "error.auth.google_required", "Google login is required."),
    AUTH_INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, 105, "error.auth.invalid_access_token", "Access token is invalid."),
    AUTH_INVALID_DEVICE_CREDENTIALS(HttpStatus.UNAUTHORIZED, 106, "error.auth.invalid_device_credentials", "Device credentials are invalid."),
    DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND, 107, "error.device.not_found", "Device registration was not found."),
    AUTH_INVALID_EMAIL_CREDENTIALS(HttpStatus.UNAUTHORIZED, 108, "error.auth.invalid_email_credentials", "Email credentials are invalid."),

    OPENAI_API_KEY_MISSING(HttpStatus.BAD_REQUEST, 200, "error.openai.api_key_missing", "OpenAI API key is missing."),
    STUDY_SETTINGS_MISSING(HttpStatus.NOT_FOUND, 201, "error.study.settings_missing", "Study settings are missing."),
    OPENAI_API_KEY_INVALID(HttpStatus.BAD_REQUEST, 202, "error.openai.api_key_invalid", "OpenAI API key is invalid."),

    ACCOUNT_FORBIDDEN(HttpStatus.FORBIDDEN, 300, "error.account.forbidden", "Account access is forbidden."),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, 301, "error.permission.denied", "Permission is denied."),
    TERMS_AGREEMENT_REQUIRED(HttpStatus.FORBIDDEN, 302, "error.terms.agreement_required", "Latest terms agreement is required."),
    TERMS_REAGREEMENT_REQUIRED(HttpStatus.FORBIDDEN, 303, "error.terms.reagreement_required", "Terms re-agreement is required."),
    NOTIFICATION_PREFERENCE_DISABLED(HttpStatus.FORBIDDEN, 304, "error.notification.preference_disabled", "Notification preference is disabled."),
    QUOTA_EXCEEDED(HttpStatus.FORBIDDEN, 305, "error.quota.exceeded", "Quota is exceeded."),
    DEVICE_NOT_REGISTERED(HttpStatus.FORBIDDEN, 306, "error.device.not_registered", "Device is not registered."),
    USER_INACTIVE(HttpStatus.FORBIDDEN, 307, "error.user.inactive", "User is inactive."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, 308, "error.email.not_verified", "Email is not verified."),
    APP_VERSION_UNSUPPORTED(HttpStatus.UPGRADE_REQUIRED, 309, "error.app.version_unsupported", "App version is unsupported."),

    RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, 400, "error.record.not_found", "Record was not found."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, 401, "error.resource.not_found", "Resource was not found."),

    VALIDATION_ERROR(HttpStatus.UNPROCESSABLE_ENTITY, 500, "error.validation", "Request validation failed."),
    STUDY_PENDING_QUESTION_EXISTS(
        HttpStatus.CONFLICT,
        501,
        "error.study.pending_question_exists",
        "A pending question already exists for this study.",
    ),
    DISPLAY_NAME_TAKEN(
        HttpStatus.CONFLICT,
        502,
        "error.profile.display_name_taken",
        "Display name is already in use.",
    ),

    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, 900, "error.internal.server_error", "Internal server error."),
    EMAIL_DELIVERY_FAILED(HttpStatus.SERVICE_UNAVAILABLE, 901, "error.email.delivery_failed", "Email delivery failed."),
    SERVER_BUSY(HttpStatus.SERVICE_UNAVAILABLE, 902, "error.server.busy", "Server is temporarily busy."),
    SERVICE_UNDER_MAINTENANCE(
        HttpStatus.SERVICE_UNAVAILABLE,
        903,
        "error.service.under_maintenance",
        "Service is temporarily unavailable for maintenance.",
    ),
    ;
}

open class ApiRuntimeException(
    val errorCode: ApiErrorCode,
    override val message: String = errorCode.debugDescription,
    val statusOverride: HttpStatus? = null,
    val requiredPermissions: List<String>? = null,
    val requiredTerms: List<Any>? = null,
    val requiredActions: List<String>? = null,
    val metadata: Map<String, Any?>? = null,
) : RuntimeException(message) {
    val status: HttpStatus
        get() = statusOverride ?: errorCode.status
}

class ApiException(
    status: HttpStatus,
    val code: ApiErrorCode,
    override val message: String,
    requiredPermissions: List<String>? = null,
    metadata: Map<String, Any?>? = null,
) : ApiRuntimeException(
    errorCode = code,
    message = message,
    statusOverride = status,
    requiredPermissions = requiredPermissions,
    metadata = metadata,
)
