package com.buddystudy.backend.auth.application.model

data class TermsResponse(
    val code: String,
    val version: String,
    val title: String,
    val url: String,
    val contentHash: String,
)

data class TermsAgreementCommand(
    val code: String,
    val action: String,
    val source: String,
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
