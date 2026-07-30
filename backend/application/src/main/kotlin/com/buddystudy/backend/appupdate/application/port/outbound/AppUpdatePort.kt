package com.buddystudy.backend.appupdate.application.port.outbound

import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignPage
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignSummary
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateUserPage
import com.buddystudy.backend.appupdate.application.model.AdminAppControlMaintenanceOverview
import com.buddystudy.backend.appupdate.application.model.AdminAppControlMaintenancePage
import com.buddystudy.backend.appupdate.application.model.AppUpdateCampaign
import com.buddystudy.backend.appupdate.application.model.AppUpdateEvent
import com.buddystudy.backend.appupdate.application.model.AppUpdateUserState
import com.buddystudy.backend.appupdate.application.model.AppControlEventCommand
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenanceCommand
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenanceWindow
import com.buddystudy.backend.appupdate.application.model.RemoteConfigPublicationStatus
import com.buddystudy.backend.appupdate.application.model.CreateAppUpdateCampaignCommand
import java.time.Instant

interface AppUpdatePort {
    suspend fun updateDeviceVersion(
        userId: Long,
        deviceId: String,
        version: String,
        build: String,
        seenAt: Instant,
    )
    suspend fun recordAppControlEvent(
        userId: Long,
        deviceId: String,
        command: AppControlEventCommand,
        recordedAt: Instant,
    ): Boolean
    suspend fun activeCampaign(platform: String): AppUpdateCampaign?
    suspend fun userState(campaignId: Long, userId: Long): AppUpdateUserState?
    suspend fun recordCheck(
        campaignId: Long,
        userId: Long,
        deviceId: String,
        version: String,
        build: String,
        checkedAt: Instant,
    ): AppUpdateUserState
    suspend fun markConverted(campaignId: Long, userId: Long, version: String, build: String, convertedAt: Instant)
    suspend fun recordEvent(campaignId: Long, userId: Long, deviceId: String, event: AppUpdateEvent, occurredAt: Instant): Boolean
    suspend fun campaigns(limit: Int, offset: Int): AdminAppUpdateCampaignPage
    suspend fun createCampaign(command: CreateAppUpdateCampaignCommand, now: Instant): AdminAppUpdateCampaignSummary
    suspend fun endCampaign(campaignId: Long, now: Instant): AdminAppUpdateCampaignSummary?
    suspend fun campaignUsers(campaignId: Long, query: String?, status: String?, limit: Int, offset: Int): AdminAppUpdateUserPage
    suspend fun maintenanceOverview(now: Instant): AdminAppControlMaintenanceOverview
    suspend fun maintenanceHistory(limit: Int, offset: Int): AdminAppControlMaintenancePage
    suspend fun activeMaintenance(now: Instant): AppControlMaintenanceWindow?
    suspend fun createMaintenance(command: AppControlMaintenanceCommand, now: Instant): AppControlMaintenanceWindow
    suspend fun endMaintenance(maintenanceId: Long, now: Instant): AppControlMaintenanceWindow?
    suspend fun updateRemoteConfigPublication(
        campaignId: Long?,
        status: RemoteConfigPublicationStatus,
        revision: Long?,
        publishedAt: Instant?,
        error: String?,
        now: Instant,
    )
}

interface AppControlRemoteConfigPort {
    suspend fun publish(policy: com.buddystudy.backend.appupdate.application.model.AppControlRemotePolicy):
        com.buddystudy.backend.appupdate.application.model.RemoteConfigPublicationResult
}
