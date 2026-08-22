package com.buddystudy.backend.community.application.port.inbound

import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignCommand
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignPage
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignSummary
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserPage

interface AdminNativeAdvertisementUseCase {
    suspend fun campaigns(limit: Int, offset: Int): AdminNativeAdvertisementCampaignPage
    suspend fun create(command: AdminNativeAdvertisementCampaignCommand): AdminNativeAdvertisementCampaignSummary
    suspend fun update(id: Long, command: AdminNativeAdvertisementCampaignCommand): AdminNativeAdvertisementCampaignSummary
    suspend fun users(
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementUserPage
}
