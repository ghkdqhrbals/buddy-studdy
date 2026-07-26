package com.buddystudy.backend.admin.management.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.admin.management.application.model.AdminMembershipTierResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserSummary
import com.buddystudy.backend.admin.management.application.model.AssignUserPlanCommand
import com.buddystudy.backend.admin.management.application.port.inbound.AdminManagementUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin")
class AdminManagementController(
    private val management: AdminManagementWebPort,
) {
    @GetMapping("/users")
    suspend fun users(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): AdminUserPageResponse = management.users(authorization.bearerToken(), query, limit, offset)

    @GetMapping("/membership-tiers")
    suspend fun tiers(
        @RequestHeader("Authorization") authorization: String?,
    ): List<AdminMembershipTierResponse> =
        management.tiers(authorization.bearerToken())

    @PatchMapping("/membership-tiers/{tierCode}")
    suspend fun updateTier(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable tierCode: String,
        @Valid @RequestBody request: UpdateMembershipTierRequest,
    ): AdminMembershipTierResponse =
        management.updateTier(authorization.bearerToken(), tierCode, request)

    @PatchMapping("/users/{userId}/membership")
    suspend fun assignPlan(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable userId: Long,
        @Valid @RequestBody request: AssignUserPlanRequest,
    ): AdminUserSummary =
        management.assignPlan(authorization.bearerToken(), userId, request)
}

data class UpdateMembershipTierRequest(
    @field:Min(0) @field:Max(1_000_000)
    var monthlyQuestionLimit: Int = 0,
)

data class AssignUserPlanRequest(
    @field:NotBlank
    var tierCode: String = "",
    @field:Min(0) @field:Max(1_000_000)
    var monthlyQuestionLimitOverride: Int? = null,
)

interface AdminManagementWebPort {
    suspend fun users(adminToken: String, query: String?, limit: Int, offset: Int): AdminUserPageResponse
    suspend fun tiers(adminToken: String): List<AdminMembershipTierResponse>
    suspend fun updateTier(
        adminToken: String,
        tierCode: String,
        request: UpdateMembershipTierRequest,
    ): AdminMembershipTierResponse
    suspend fun assignPlan(
        adminToken: String,
        userId: Long,
        request: AssignUserPlanRequest,
    ): AdminUserSummary
}

@Component
class AdminManagementWebAdapter(
    private val authentication: AdminAnalyticsUseCase,
    private val management: AdminManagementUseCase,
) : AdminManagementWebPort {
    override suspend fun users(
        adminToken: String,
        query: String?,
        limit: Int,
        offset: Int,
    ): AdminUserPageResponse {
        authentication.validate(adminToken)
        return management.users(query, limit, offset)
    }

    override suspend fun tiers(adminToken: String): List<AdminMembershipTierResponse> {
        authentication.validate(adminToken)
        return management.tiers()
    }

    override suspend fun updateTier(
        adminToken: String,
        tierCode: String,
        request: UpdateMembershipTierRequest,
    ): AdminMembershipTierResponse {
        authentication.validate(adminToken)
        return management.updateTier(tierCode, request.monthlyQuestionLimit)
    }

    override suspend fun assignPlan(
        adminToken: String,
        userId: Long,
        request: AssignUserPlanRequest,
    ): AdminUserSummary {
        authentication.validate(adminToken)
        return management.assignPlan(
            userId,
            AssignUserPlanCommand(request.tierCode, request.monthlyQuestionLimitOverride),
        )
    }
}

private fun String?.bearerToken(): String =
    this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
