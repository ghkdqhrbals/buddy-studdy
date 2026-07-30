package com.buddystudy.backend.appupdate.application.port.inbound

import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignPage
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignSummary
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateUserPage
import com.buddystudy.backend.appupdate.application.model.AppUpdateCheckCommand
import com.buddystudy.backend.appupdate.application.model.AppUpdateDecision
import com.buddystudy.backend.appupdate.application.model.AppUpdateEvent
import com.buddystudy.backend.appupdate.application.model.CreateAppUpdateCampaignCommand
import com.buddystudy.backend.auth.Principal

interface AppUpdateUseCase {
    suspend fun check(principal: Principal, command: AppUpdateCheckCommand): AppUpdateDecision
    suspend fun recordEvent(principal: Principal, campaignId: Long, event: AppUpdateEvent)
}

interface AdminAppUpdateUseCase {
    suspend fun campaigns(limit: Int, offset: Int): AdminAppUpdateCampaignPage
    suspend fun create(command: CreateAppUpdateCampaignCommand): AdminAppUpdateCampaignSummary
    suspend fun end(campaignId: Long): AdminAppUpdateCampaignSummary
    suspend fun users(campaignId: Long, query: String?, status: String?, limit: Int, offset: Int): AdminAppUpdateUserPage
}
