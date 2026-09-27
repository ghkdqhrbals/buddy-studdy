package com.buddystudy.backend.notification.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.notification.application.model.*

interface ManagePushCampaignsUseCase {
    suspend fun preview(command: PushCampaignCommand): PushCampaignPreviewResponse
    suspend fun create(command: PushCampaignCommand): PushCampaignResponse
    suspend fun campaigns(limit: Int, offset: Int): PushCampaignPageResponse
    suspend fun campaign(id: String): PushCampaignResponse
    suspend fun send(id: String): PushCampaignResponse
}

interface DispatchPushCampaignsUseCase {
    suspend fun dispatchBatch(): Int
}

interface TrackNotificationOpenUseCase {
    suspend fun open(principal: Principal, notificationId: Long, source: NotificationOpenSource): NotificationMutationResponse
}
