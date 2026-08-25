package com.buddystudy.backend.community.application.port.inbound

import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignCommand
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignPage
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignSummary
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserPage
import com.buddystudy.backend.community.application.model.AdminNativeAdPlacementPolicyCommand
import com.buddystudy.backend.community.application.model.AdminNativeAdPlacementPolicyResponse

interface AdminNativeAdvertisementUseCase {
    suspend fun campaigns(
        query: String?,
        status: String?,
        audience: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementCampaignPage
    suspend fun create(command: AdminNativeAdvertisementCampaignCommand): AdminNativeAdvertisementCampaignSummary
    suspend fun update(id: Long, command: AdminNativeAdvertisementCampaignCommand): AdminNativeAdvertisementCampaignSummary
    suspend fun users(
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementUserPage
    suspend fun placementPolicy(placement: String): AdminNativeAdPlacementPolicyResponse
    suspend fun updatePlacementPolicy(
        placement: String,
        command: AdminNativeAdPlacementPolicyCommand,
    ): AdminNativeAdPlacementPolicyResponse
}
