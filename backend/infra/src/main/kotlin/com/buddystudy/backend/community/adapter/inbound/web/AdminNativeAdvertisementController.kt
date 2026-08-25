package com.buddystudy.backend.community.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignCommand
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignPage
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignSummary
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserPage
import com.buddystudy.backend.community.application.model.AdminNativeAdPlacementPolicyCommand
import com.buddystudy.backend.community.application.model.AdminNativeAdPlacementPolicyResponse
import com.buddystudy.backend.community.application.port.inbound.AdminNativeAdvertisementUseCase
import com.buddystudy.community.domain.entity.NativeAdvertisementAudience
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@RestController
@RequestMapping("/api/v1/admin/native-ad-campaigns")
class AdminNativeAdvertisementController(
    private val advertisements: AdminNativeAdvertisementWebPort,
) {
    @GetMapping
    suspend fun campaigns(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) audience: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): AdminNativeAdvertisementCampaignPage =
        advertisements.campaigns(
            authorization.adminBearerToken(),
            query,
            status,
            audience,
            limit,
            offset,
        )

    @GetMapping("/{campaignId}/users")
    suspend fun users(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable campaignId: Long,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): AdminNativeAdvertisementUserPage =
        advertisements.users(
            authorization.adminBearerToken(),
            campaignId,
            query,
            status,
            limit,
            offset,
        )

    @PostMapping
    suspend fun create(
        @RequestHeader("Authorization") authorization: String?,
        @Valid @RequestBody request: AdminNativeAdvertisementCampaignRequest,
    ): AdminNativeAdvertisementCampaignSummary =
        advertisements.create(authorization.adminBearerToken(), request)

    @PutMapping("/{campaignId}")
    suspend fun update(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable campaignId: Long,
        @Valid @RequestBody request: AdminNativeAdvertisementCampaignRequest,
    ): AdminNativeAdvertisementCampaignSummary =
        advertisements.update(authorization.adminBearerToken(), campaignId, request)
}

@RestController
@RequestMapping("/api/v1/admin/native-ad-placement-policies")
class AdminNativeAdPlacementPolicyController(
    private val policies: AdminNativeAdPlacementPolicyWebPort,
) {
    @GetMapping("/{placement}")
    suspend fun policy(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable placement: String,
    ): AdminNativeAdPlacementPolicyResponse = policies.policy(authorization.adminBearerToken(), placement)

    @PutMapping("/{placement}")
    suspend fun updatePolicy(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable placement: String,
        @Valid @RequestBody request: AdminNativeAdPlacementPolicyRequest,
    ): AdminNativeAdPlacementPolicyResponse = policies.updatePolicy(authorization.adminBearerToken(), placement, request)
}

data class AdminNativeAdPlacementPolicyRequest(
    @field:NotBlank var placement: String = "COMMUNITY_FEED",
    var enabled: Boolean = false,
    @field:Min(0) @field:Max(100) var dailyDeliveryCap: Int = 2,
    @field:Min(60) @field:Max(2_592_000) var minimumSecondsBetweenDeliveries: Int = 21_600,
    @field:Min(4) @field:Max(100) var minimumFeedItemCount: Int = 4,
    @field:Min(2) @field:Max(99) var earliestPosition: Int = 2,
    @field:Min(2) @field:Max(99) var latestPosition: Int = 7,
    var startsAt: Instant? = null,
    var endsAt: Instant? = null,
)

interface AdminNativeAdPlacementPolicyWebPort {
    suspend fun policy(adminToken: String, placement: String): AdminNativeAdPlacementPolicyResponse
    suspend fun updatePolicy(
        adminToken: String,
        placement: String,
        request: AdminNativeAdPlacementPolicyRequest,
    ): AdminNativeAdPlacementPolicyResponse
}

@Component
class AdminNativeAdPlacementPolicyWebAdapter(
    private val authentication: AdminAnalyticsUseCase,
    private val policies: AdminNativeAdvertisementUseCase,
) : AdminNativeAdPlacementPolicyWebPort {
    override suspend fun policy(adminToken: String, placement: String): AdminNativeAdPlacementPolicyResponse {
        authentication.validate(adminToken)
        return policies.placementPolicy(placement)
    }

    override suspend fun updatePolicy(
        adminToken: String,
        placement: String,
        request: AdminNativeAdPlacementPolicyRequest,
    ): AdminNativeAdPlacementPolicyResponse {
        authentication.validate(adminToken)
        return policies.updatePlacementPolicy(placement, request.toCommand())
    }
}

private fun AdminNativeAdPlacementPolicyRequest.toCommand() = AdminNativeAdPlacementPolicyCommand(
    placement = placement,
    enabled = enabled,
    dailyDeliveryCap = dailyDeliveryCap,
    minimumSecondsBetweenDeliveries = minimumSecondsBetweenDeliveries,
    minimumFeedItemCount = minimumFeedItemCount,
    earliestPosition = earliestPosition,
    latestPosition = latestPosition,
    startsAt = startsAt,
    endsAt = endsAt,
)

data class AdminNativeAdvertisementCampaignRequest(
    @field:NotBlank var campaignKey: String = "",
    var audience: NativeAdvertisementAudience = NativeAdvertisementAudience.ALL,
    @field:NotBlank var disclosureKo: String = "(광고)",
    @field:NotBlank var disclosureEn: String = "(Ad)",
    @field:NotBlank var disclosureJa: String = "（広告）",
    @field:NotBlank var titleKo: String = "",
    @field:NotBlank var titleEn: String = "",
    @field:NotBlank var titleJa: String = "",
    var bodyKo: String? = null,
    var bodyEn: String? = null,
    var bodyJa: String? = null,
    var imageUrl: String? = null,
    var affiliateDisclosureKo: String? = null,
    var affiliateDisclosureEn: String? = null,
    var affiliateDisclosureJa: String? = null,
    @field:NotBlank var destinationUrl: String = "",
    @field:DecimalMin("0.0") @field:DecimalMax("10.0") var basePriority: BigDecimal = BigDecimal.ONE,
    @field:DecimalMin("0.0") @field:DecimalMax("10.0") var authenticatedRelevance: BigDecimal = BigDecimal.ONE,
    @field:DecimalMin("0.0") @field:DecimalMax("10.0") var anonymousRelevance: BigDecimal = BigDecimal.ONE,
    @field:Min(0) @field:Max(100) var dailySelectionCap: Int = 2,
    @field:Min(0) @field:Max(2_592_000) var minimumSecondsBetweenSelections: Int = 21_600,
    @field:Min(0) @field:Max(31_536_000) var postViewCooldownSeconds: Int = 604_800,
    @field:Min(1) @field:Max(100) var minimumFeedItemCount: Int = 4,
    @field:Min(0) @field:Max(99) var earliestPosition: Int = 2,
    @field:Min(0) @field:Max(99) var latestPosition: Int = 7,
    var active: Boolean = true,
    var startsAt: Instant? = null,
    var endsAt: Instant? = null,
)

interface AdminNativeAdvertisementWebPort {
    suspend fun campaigns(
        adminToken: String,
        query: String?,
        status: String?,
        audience: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementCampaignPage
    suspend fun create(
        adminToken: String,
        request: AdminNativeAdvertisementCampaignRequest,
    ): AdminNativeAdvertisementCampaignSummary
    suspend fun update(
        adminToken: String,
        campaignId: Long,
        request: AdminNativeAdvertisementCampaignRequest,
    ): AdminNativeAdvertisementCampaignSummary
    suspend fun users(
        adminToken: String,
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementUserPage
}

@Component
class AdminNativeAdvertisementWebAdapter(
    private val authentication: AdminAnalyticsUseCase,
    private val advertisements: AdminNativeAdvertisementUseCase,
) : AdminNativeAdvertisementWebPort {
    override suspend fun campaigns(
        adminToken: String,
        query: String?,
        status: String?,
        audience: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementCampaignPage {
        authentication.validate(adminToken)
        return advertisements.campaigns(query, status, audience, limit, offset)
    }

    override suspend fun create(
        adminToken: String,
        request: AdminNativeAdvertisementCampaignRequest,
    ): AdminNativeAdvertisementCampaignSummary {
        authentication.validate(adminToken)
        return advertisements.create(request.toCommand())
    }

    override suspend fun update(
        adminToken: String,
        campaignId: Long,
        request: AdminNativeAdvertisementCampaignRequest,
    ): AdminNativeAdvertisementCampaignSummary {
        authentication.validate(adminToken)
        return advertisements.update(campaignId, request.toCommand())
    }

    override suspend fun users(
        adminToken: String,
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementUserPage {
        authentication.validate(adminToken)
        return advertisements.users(campaignId, query, status, limit, offset)
    }
}

private fun AdminNativeAdvertisementCampaignRequest.toCommand() = AdminNativeAdvertisementCampaignCommand(
    campaignKey = campaignKey,
    audience = audience,
    disclosureKo = disclosureKo,
    disclosureEn = disclosureEn,
    disclosureJa = disclosureJa,
    titleKo = titleKo,
    titleEn = titleEn,
    titleJa = titleJa,
    bodyKo = bodyKo,
    bodyEn = bodyEn,
    bodyJa = bodyJa,
    imageUrl = imageUrl,
    affiliateDisclosureKo = affiliateDisclosureKo,
    affiliateDisclosureEn = affiliateDisclosureEn,
    affiliateDisclosureJa = affiliateDisclosureJa,
    destinationUrl = destinationUrl,
    basePriority = basePriority,
    authenticatedRelevance = authenticatedRelevance,
    anonymousRelevance = anonymousRelevance,
    dailySelectionCap = dailySelectionCap,
    minimumSecondsBetweenSelections = minimumSecondsBetweenSelections,
    postViewCooldownSeconds = postViewCooldownSeconds,
    minimumFeedItemCount = minimumFeedItemCount,
    earliestPosition = earliestPosition,
    latestPosition = latestPosition,
    active = active,
    startsAt = startsAt,
    endsAt = endsAt,
)

private fun String?.adminBearerToken(): String =
    this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
