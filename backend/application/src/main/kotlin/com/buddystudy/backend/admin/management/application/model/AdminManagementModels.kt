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
    val currentPeriodQuestionLimitOverride: Int?,
    val baseLimit: Int = 0,
    val bonusLimit: Int = 0,
    val usedCount: Int,
    val reservedCount: Int = 0,
    val remainingCount: Int,
    val quotaPolicyVersion: Int = 2,
    val periodStartedAt: Instant,
    val resetAt: Instant,
    val createdAt: Instant,
    val appVersion: String? = null,
    val appBuild: String? = null,
    val appVersionSeenAt: Instant? = null,
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

data class AdminFeedbackSummary(
    val id: Long,
    val userId: Long?,
    val deviceId: String?,
    val email: String?,
    val displayName: String?,
    val content: String,
    val status: String,
    val reviewedAt: Instant?,
    val repliedAt: Instant?,
    val createdAt: Instant,
)

data class AdminFeedbackPageResponse(
    val feedback: List<AdminFeedbackSummary>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
) : PageResponse

data class AdminNotificationCommand(
    val title: String,
    val body: String,
    val deepLink: String?,
)

data class AdminNotificationDispatchResponse(
    val eventId: String,
    val status: String = "QUEUED",
    val targetUserId: Long?,
    val targetDeviceId: String?,
    val deepLink: String,
    val feedbackId: Long? = null,
)
