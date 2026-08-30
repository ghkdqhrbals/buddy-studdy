package com.buddystudy.backend.admin.management.application.service

import com.buddystudy.backend.admin.management.application.model.AdminMembershipTierResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserSummary
import com.buddystudy.backend.admin.management.application.model.AssignUserPlanCommand
import com.buddystudy.backend.admin.management.application.port.inbound.AdminManagementUseCase
import com.buddystudy.backend.admin.management.application.port.outbound.AdminManagementPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminManagementService(
    private val management: AdminManagementPort,
) : AdminManagementUseCase {
    @Transactional(readOnly = true)
    override suspend fun users(query: String?, limit: Int, offset: Int): AdminUserPageResponse =
        management.users(query?.trim()?.takeIf(String::isNotEmpty), limit.coerceIn(1, 100), offset.coerceAtLeast(0))

    @Transactional(readOnly = true)
    override suspend fun tiers(): List<AdminMembershipTierResponse> = management.tiers()

    @Transactional
    override suspend fun updateTier(
        tierCode: String,
        monthlyQuestionLimit: Int?,
        monthlyVoiceSecondsLimit: Int?,
    ): AdminMembershipTierResponse {
        if (monthlyQuestionLimit == null && monthlyVoiceSecondsLimit == null) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "At least one membership tier limit is required.")
        }
        if (monthlyQuestionLimit?.let { it !in 0..1_000_000 } == true) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Monthly question limit is invalid.")
        }
        if (monthlyVoiceSecondsLimit?.let { it !in 0..31_536_000 } == true) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Monthly voice limit is invalid.")
        }
        val normalizedTierCode = tierCode.trim()
        return management.updateTierLimits(normalizedTierCode, monthlyQuestionLimit, monthlyVoiceSecondsLimit)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Membership tier not found.")
    }

    @Transactional
    override suspend fun assignPlan(userId: Long, command: AssignUserPlanCommand): AdminUserSummary {
        if (command.monthlyQuestionLimitOverride?.let { it !in 0..1_000_000 } == true) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "Monthly question limit override is invalid.")
        }
        return management.assignPlan(userId, command.copy(tierCode = command.tierCode.trim()))
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "User or membership tier not found.")
    }

    @Transactional
    override suspend fun setCurrentPeriodQuestionLimit(
        userId: Long,
        questionLimitOverride: Int?,
    ): AdminUserSummary {
        if (questionLimitOverride?.let { it !in 0..1_000_000 } == true) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Current period question limit override is invalid.",
            )
        }
        return management.setCurrentPeriodQuestionLimit(userId, questionLimitOverride)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "User not found.")
    }

    @Transactional
    override suspend fun setVoiceLimit(
        userId: Long,
        monthlyVoiceSecondsLimitOverride: Int?,
    ): AdminUserSummary {
        if (monthlyVoiceSecondsLimitOverride?.let { it !in 0..31_536_000 } == true) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Monthly Voice Tutor limit override is invalid.",
            )
        }
        return management.setVoiceLimit(userId, monthlyVoiceSecondsLimitOverride)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "User not found.")
    }
}
