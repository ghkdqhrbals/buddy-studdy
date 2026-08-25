package com.buddystudy.backend.auth.application.model

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus

enum class TermsType(val code: String) {
    TERMS_OF_SERVICE("TERMS_OF_SERVICE"),
    PRIVACY_POLICY("PRIVACY_POLICY"),
    MARKETING_NOTIFICATION("MARKETING_NOTIFICATION");

    companion object {
        fun parse(value: String): TermsType =
            entries.firstOrNull { it.name == value.trim().uppercase() || it.code == value.trim().uppercase() }
                ?: throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Invalid terms type.")
    }
}

enum class NotificationPreferenceType(val key: String) {
    QUESTION_NOTIFICATION("question_notification"),
    MARKETING_NOTIFICATION("marketing_notification");

    companion object {
        fun parse(value: String): NotificationPreferenceType {
            val normalized = value.trim()
            val storageKey = normalized.lowercase()
            return entries.firstOrNull { it.name == normalized.uppercase() || it.key == storageKey }
                ?: throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Invalid notification preference type.")
        }
    }
}

data class TermsResponse(
    val type: TermsType,
    val code: String,
    val version: String,
    val title: String,
    val url: String,
    val contentHash: String,
    val required: Boolean = true,
    val mutable: Boolean = false,
    val agreed: Boolean = false,
)

data class TermsAgreementCommand(
    val type: TermsType,
    val action: String,
    val source: String,
    val version: String? = null,
    val contentHash: String? = null,
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val appVersion: String? = null,
)

data class PermissionEvaluationResponse(
    val permissionCode: String,
    val granted: Boolean,
    val failureCode: String? = null,
    val reason: String? = null,
    val requiredTerms: List<TermsResponse> = emptyList(),
    val requiredActions: List<String> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap(),
)

data class PermissionEvaluationsResponse(
    val permissions: List<PermissionEvaluationResponse>,
)

data class NotificationPreferenceResponse(
    val type: NotificationPreferenceType,
    val key: String,
    val enabled: Boolean,
)

data class NotificationPreferenceCommand(
    val type: NotificationPreferenceType,
    val enabled: Boolean,
)
