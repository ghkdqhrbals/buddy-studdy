package com.buddystudy.backend.notification.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.notification.application.model.*
import com.buddystudy.backend.notification.application.port.inbound.ManagePushCampaignsUseCase
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/push-campaigns")
class AdminPushCampaignController(private val campaigns: AdminPushCampaignWebPort) {
    @GetMapping
    suspend fun list(@RequestHeader("Authorization", required = false) authorization: String?,
        @RequestParam(defaultValue = "20") limit: Int, @RequestParam(defaultValue = "0") offset: Int): PushCampaignPageResponse =
        campaigns.list(authorization.token(), limit, offset)

    @GetMapping("/{id}")
    suspend fun campaign(@RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable id: String): PushCampaignResponse = campaigns.campaign(authorization.token(), id)

    @PostMapping("/preview")
    suspend fun preview(@RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody command: PushCampaignCommand): PushCampaignPreviewResponse = campaigns.preview(authorization.token(), command)

    @PostMapping
    suspend fun create(@RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody command: PushCampaignCommand): PushCampaignResponse = campaigns.create(authorization.token(), command)

    @PostMapping("/{id}/send")
    suspend fun send(@RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable id: String): PushCampaignResponse = campaigns.send(authorization.token(), id)
}

interface AdminPushCampaignWebPort {
    suspend fun list(token: String, limit: Int, offset: Int): PushCampaignPageResponse
    suspend fun campaign(token: String, id: String): PushCampaignResponse
    suspend fun preview(token: String, command: PushCampaignCommand): PushCampaignPreviewResponse
    suspend fun create(token: String, command: PushCampaignCommand): PushCampaignResponse
    suspend fun send(token: String, id: String): PushCampaignResponse
}

@Component
class AdminPushCampaignWebAdapter(
    private val authentication: AdminAnalyticsUseCase,
    private val campaigns: ManagePushCampaignsUseCase,
) : AdminPushCampaignWebPort {
    override suspend fun list(token: String, limit: Int, offset: Int): PushCampaignPageResponse {
        authentication.validate(token)
        return campaigns.campaigns(limit, offset)
    }
    override suspend fun campaign(token: String, id: String): PushCampaignResponse {
        authentication.validate(token)
        return campaigns.campaign(id)
    }
    override suspend fun preview(token: String, command: PushCampaignCommand): PushCampaignPreviewResponse {
        authentication.validate(token)
        return campaigns.preview(command)
    }
    override suspend fun create(token: String, command: PushCampaignCommand): PushCampaignResponse {
        authentication.validate(token)
        return campaigns.create(command)
    }
    override suspend fun send(token: String, id: String): PushCampaignResponse {
        authentication.validate(token)
        return campaigns.send(id)
    }
}

private fun String?.token(): String = this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
