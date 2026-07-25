package com.buddystudy.backend.admin.management.application.model

import com.buddystudy.backend.common.application.model.PageResponse
import java.time.Instant

data class AdminUserSummary(
    val id: Long,
    val email: String,
    val displayName: String,
    val provider: String,
    val status: String,
    val tierCode: String,
    val tierDescription: String,
    val monthlyLimit: Int,
    val monthlyLimitOverride: Int?,
    val usedCount: Int,
    val remainingCount: Int,
    val resetAt: Instant,
    val createdAt: Instant,
)

data class AdminUserPageResponse(
    val users: List<AdminUserSummary>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
) : PageResponse

data class AdminMembershipTierResponse(
    val tierCode: String,
    val monthlyQuestionLimit: Int,
    val description: String,
)

data class AssignUserPlanCommand(
    val tierCode: String,
    val monthlyQuestionLimitOverride: Int?,
)
