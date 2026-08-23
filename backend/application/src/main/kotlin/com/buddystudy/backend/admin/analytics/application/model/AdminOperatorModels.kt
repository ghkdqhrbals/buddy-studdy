package com.buddystudy.backend.admin.analytics.application.model

import java.time.Instant

data class AdminOperatorPrincipal(
    val id: Long,
    val username: String,
    val displayName: String,
    val status: String,
)

data class AdminOperatorSummary(
    val id: Long,
    val username: String,
    val displayName: String,
    val status: String,
    val lastLoginAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminOperatorPageResponse(
    val operators: List<AdminOperatorSummary>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
)

data class CreateAdminOperatorCommand(
    val username: String,
    val displayName: String,
    val password: String,
)

data class UpdateAdminOperatorCommand(
    val displayName: String?,
    val status: String?,
    val password: String?,
)

data class AdminSessionResponse(
    val username: String,
)
