package com.buddystudy.backend.appupdate.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignPage
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignSummary
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateUserPage
import com.buddystudy.backend.appupdate.application.model.AppUpdateCheckCommand
import com.buddystudy.backend.appupdate.application.model.AppUpdateDecision
import com.buddystudy.backend.appupdate.application.model.AppUpdateEvent
import com.buddystudy.backend.appupdate.application.model.AppUpdateMode
import com.buddystudy.backend.appupdate.application.model.CreateAppUpdateCampaignCommand
import com.buddystudy.backend.appupdate.application.port.inbound.AdminAppUpdateUseCase
import com.buddystudy.backend.appupdate.application.port.inbound.AppUpdateUseCase
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/app-updates")
class AppUpdateController(
    private val updates: AppUpdateWebPort,
) {
    @PostMapping("/check")
    suspend fun check(
        authentication: Authentication,
        @Valid @RequestBody request: AppUpdateCheckRequest,
    ): AppUpdateDecision = updates.check(authentication.principalOrThrow(), request)

    @PostMapping("/{campaignId}/events")
    suspend fun event(
        authentication: Authentication,
        @PathVariable campaignId: Long,
        @Valid @RequestBody request: AppUpdateEventRequest,
    ): ResponseEntity<Unit> {
        updates.event(authentication.principalOrThrow(), campaignId, request)
        return ResponseEntity.noContent().build()
    }
}

@RestController
@RequestMapping("/api/v1/admin/app-updates")
class AdminAppUpdateController(
    private val updates: AdminAppUpdateWebPort,
) {
    @GetMapping
    suspend fun campaigns(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): AdminAppUpdateCampaignPage = updates.campaigns(authorization.bearerToken(), limit, offset)

    @PostMapping
    suspend fun create(
        @RequestHeader("Authorization") authorization: String?,
        @Valid @RequestBody request: CreateAppUpdateCampaignRequest,
    ): AdminAppUpdateCampaignSummary = updates.create(authorization.bearerToken(), request)

    @PostMapping("/{campaignId}/end")
    suspend fun end(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable campaignId: Long,
    ): AdminAppUpdateCampaignSummary = updates.end(authorization.bearerToken(), campaignId)

    @GetMapping("/{campaignId}/users")
    suspend fun users(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable campaignId: Long,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): AdminAppUpdateUserPage =
        updates.users(authorization.bearerToken(), campaignId, query, status, limit, offset)
}

data class AppUpdateCheckRequest(
    var platform: String = "ios",
    @field:NotBlank var currentVersion: String = "",
    @field:NotBlank var currentBuild: String = "",
    var language: String = "ko",
)

data class AppUpdateEventRequest(
    @field:NotBlank var event: String = "",
)

data class CreateAppUpdateCampaignRequest(
    var platform: String = "ios",
    @field:NotBlank var targetVersion: String = "",
    @field:NotBlank var targetBuild: String = "",
    var mode: AppUpdateMode = AppUpdateMode.OPTIONAL,
    @field:NotBlank var titleKo: String = "",
    @field:NotBlank var titleEn: String = "",
    @field:NotBlank var titleJa: String = "",
    @field:NotBlank var messageKo: String = "",
    @field:NotBlank var messageEn: String = "",
    @field:NotBlank var messageJa: String = "",
    @field:NotBlank var appStoreUrl: String = "",
)

interface AppUpdateWebPort {
    suspend fun check(principal: Principal, request: AppUpdateCheckRequest): AppUpdateDecision
    suspend fun event(principal: Principal, campaignId: Long, request: AppUpdateEventRequest)
}

interface AdminAppUpdateWebPort {
    suspend fun campaigns(adminToken: String, limit: Int, offset: Int): AdminAppUpdateCampaignPage
    suspend fun create(adminToken: String, request: CreateAppUpdateCampaignRequest): AdminAppUpdateCampaignSummary
    suspend fun end(adminToken: String, campaignId: Long): AdminAppUpdateCampaignSummary
    suspend fun users(
        adminToken: String,
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminAppUpdateUserPage
}

@Component
class AppUpdateWebAdapter(
    private val updates: AppUpdateUseCase,
) : AppUpdateWebPort {
    override suspend fun check(principal: Principal, request: AppUpdateCheckRequest): AppUpdateDecision =
        updates.check(
            principal,
            AppUpdateCheckCommand(request.platform, request.currentVersion, request.currentBuild, request.language),
        )

    override suspend fun event(principal: Principal, campaignId: Long, request: AppUpdateEventRequest) =
        updates.recordEvent(principal, campaignId, AppUpdateEvent.valueOf(request.event.trim().uppercase()))
}

@Component
class AdminAppUpdateWebAdapter(
    private val authentication: AdminAnalyticsUseCase,
    private val updates: AdminAppUpdateUseCase,
) : AdminAppUpdateWebPort {
    override suspend fun campaigns(adminToken: String, limit: Int, offset: Int): AdminAppUpdateCampaignPage {
        authentication.validate(adminToken)
        return updates.campaigns(limit, offset)
    }

    override suspend fun create(
        adminToken: String,
        request: CreateAppUpdateCampaignRequest,
    ): AdminAppUpdateCampaignSummary {
        authentication.validate(adminToken)
        return updates.create(
            CreateAppUpdateCampaignCommand(
                platform = request.platform,
                targetVersion = request.targetVersion,
                targetBuild = request.targetBuild,
                mode = request.mode,
                titleKo = request.titleKo,
                titleEn = request.titleEn,
                titleJa = request.titleJa,
                messageKo = request.messageKo,
                messageEn = request.messageEn,
                messageJa = request.messageJa,
                appStoreUrl = request.appStoreUrl,
                createdBy = "monitoring-admin",
            ),
        )
    }

    override suspend fun end(adminToken: String, campaignId: Long): AdminAppUpdateCampaignSummary {
        authentication.validate(adminToken)
        return updates.end(campaignId)
    }

    override suspend fun users(
        adminToken: String,
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminAppUpdateUserPage {
        authentication.validate(adminToken)
        return updates.users(campaignId, query, status, limit, offset)
    }
}

private fun String?.bearerToken(): String =
    this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
