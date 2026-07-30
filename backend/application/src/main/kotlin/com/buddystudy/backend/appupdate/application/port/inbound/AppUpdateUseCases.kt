package com.buddystudy.backend.appupdate.application.port.inbound

import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignPage
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignSummary
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateUserPage
import com.buddystudy.backend.appupdate.application.model.AdminAppControlMaintenanceOverview
import com.buddystudy.backend.appupdate.application.model.AdminAppControlMaintenancePage
import com.buddystudy.backend.appupdate.application.model.AppUpdateCheckCommand
import com.buddystudy.backend.appupdate.application.model.AppUpdateDecision
import com.buddystudy.backend.appupdate.application.model.AppUpdateEvent
import com.buddystudy.backend.appupdate.application.model.AppControlEventCommand
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenanceCommand
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenanceWindow
import com.buddystudy.backend.appupdate.application.model.CreateAppUpdateCampaignCommand
import com.buddystudy.backend.auth.Principal

interface AppUpdateUseCase {
    suspend fun check(principal: Principal, command: AppUpdateCheckCommand): AppUpdateDecision
    suspend fun recordEvent(principal: Principal, campaignId: Long, event: AppUpdateEvent)
    suspend fun recordAppControlEvent(principal: Principal, command: AppControlEventCommand)
}

interface AdminAppUpdateUseCase {
    suspend fun campaigns(limit: Int, offset: Int): AdminAppUpdateCampaignPage
    suspend fun create(command: CreateAppUpdateCampaignCommand): AdminAppUpdateCampaignSummary
    suspend fun end(campaignId: Long): AdminAppUpdateCampaignSummary
    suspend fun users(campaignId: Long, query: String?, status: String?, limit: Int, offset: Int): AdminAppUpdateUserPage
    suspend fun maintenanceOverview(): AdminAppControlMaintenanceOverview
    suspend fun maintenanceHistory(limit: Int, offset: Int): AdminAppControlMaintenancePage
    suspend fun publishCurrentPolicy(): AdminAppUpdateCampaignSummary?
    suspend fun activateMaintenance(command: AppControlMaintenanceCommand): AppControlMaintenanceWindow
    suspend fun endMaintenance(maintenanceId: Long): AppControlMaintenanceWindow
    suspend fun endCurrentMaintenance(): AppControlMaintenanceWindow?
}
