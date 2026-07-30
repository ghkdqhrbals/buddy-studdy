package com.buddystudy.backend.appupdate.application.service

import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignPage
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignSummary
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateUserPage
import com.buddystudy.backend.appupdate.application.model.AppUpdateCheckCommand
import com.buddystudy.backend.appupdate.application.model.AppUpdateDecision
import com.buddystudy.backend.appupdate.application.model.AppUpdateEvent
import com.buddystudy.backend.appupdate.application.model.AppUpdateMode
import com.buddystudy.backend.appupdate.application.model.AppControlEventCommand
import com.buddystudy.backend.appupdate.application.model.AppControlEventType
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenanceCommand
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenancePolicy
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenanceWindow
import com.buddystudy.backend.appupdate.application.model.AppControlRemotePolicy
import com.buddystudy.backend.appupdate.application.model.AppControlUpdatePolicy
import com.buddystudy.backend.appupdate.application.model.CreateAppUpdateCampaignCommand
import com.buddystudy.backend.appupdate.application.model.LocalizedAppControlContent
import com.buddystudy.backend.appupdate.application.model.RemoteConfigPublicationStatus
import com.buddystudy.backend.appupdate.application.port.inbound.AdminAppUpdateUseCase
import com.buddystudy.backend.appupdate.application.port.inbound.AppUpdateUseCase
import com.buddystudy.backend.appupdate.application.port.outbound.AppControlRemoteConfigPort
import com.buddystudy.backend.appupdate.application.port.outbound.AppUpdatePort
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class AppUpdateService(
    private val updates: AppUpdatePort,
) : AppUpdateUseCase {
    @Transactional
    override suspend fun check(principal: Principal, command: AppUpdateCheckCommand): AppUpdateDecision {
        val platform = command.platform.trim().lowercase().ifBlank { "ios" }
        val version = command.currentVersion.cleanVersion()
        val build = command.currentBuild.cleanVersion()
        val language = command.language.trim().lowercase().takeIf { it in setOf("ko", "en", "ja") } ?: "ko"
        val now = Instant.now()
        updates.updateDeviceVersion(principal.userId, principal.deviceId, version, build, now)
        val campaign = updates.activeCampaign(platform) ?: return AppUpdateDecision(false, false)
        if (VersionNumber(version, build) >= VersionNumber(campaign.targetVersion, campaign.targetBuild)) {
            updates.userState(campaign.id, principal.userId)?.let {
                updates.markConverted(campaign.id, principal.userId, version, build, now)
            }
            return AppUpdateDecision(false, false)
        }
        val state = updates.recordCheck(campaign.id, principal.userId, principal.deviceId, version, build, now)
        val shouldPresent = campaign.mode == AppUpdateMode.FORCE || state.dismissedAt == null
        return AppUpdateDecision(
            updateAvailable = true,
            shouldPresent = shouldPresent,
            campaignId = campaign.id,
            mode = campaign.mode,
            targetVersion = campaign.targetVersion,
            targetBuild = campaign.targetBuild,
            title = campaign.localizedTitle(language),
            message = campaign.localizedMessage(language),
            appStoreUrl = campaign.appStoreUrl,
        )
    }

    @Transactional
    override suspend fun recordEvent(principal: Principal, campaignId: Long, event: AppUpdateEvent) {
        if (!updates.recordEvent(campaignId, principal.userId, principal.deviceId, event, Instant.now())) {
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "App update campaign state not found.")
        }
    }

    @Transactional
    override suspend fun recordAppControlEvent(principal: Principal, command: AppControlEventCommand) {
        val eventId = command.eventId.trim().take(191)
        if (eventId.isBlank() || !eventId.matches(Regex("[A-Za-z0-9._:-]+"))) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "App control event id is invalid.",
            )
        }
        val version = command.currentVersion.cleanVersion()
        val build = command.currentBuild.cleanVersion()
        val now = Instant.now()
        updates.updateDeviceVersion(principal.userId, principal.deviceId, version, build, now)
        val inserted = updates.recordAppControlEvent(
            principal.userId,
            principal.deviceId,
            command.copy(
                eventId = eventId,
                platform = command.platform.trim().lowercase().ifBlank { "ios" },
                currentVersion = version,
                currentBuild = build,
                policyId = command.policyId?.trim()?.take(191)?.takeIf(String::isNotEmpty),
                evaluatedAction = command.evaluatedAction?.trim()?.take(64)?.takeIf(String::isNotEmpty),
            ),
            now,
        )
        val campaignId = command.campaignId
        if (inserted && campaignId != null) {
            updates.recordCheck(
                campaignId,
                principal.userId,
                principal.deviceId,
                version,
                build,
                now,
            )
            when (command.event) {
                AppControlEventType.PROMPT_SHOWN ->
                    updates.recordEvent(
                        campaignId,
                        principal.userId,
                        principal.deviceId,
                        AppUpdateEvent.SHOWN,
                        now,
                    )
                AppControlEventType.DISMISSED ->
                    updates.recordEvent(
                        campaignId,
                        principal.userId,
                        principal.deviceId,
                        AppUpdateEvent.DISMISSED,
                        now,
                    )
                AppControlEventType.STORE_OPENED ->
                    updates.recordEvent(
                        campaignId,
                        principal.userId,
                        principal.deviceId,
                        AppUpdateEvent.APP_STORE_OPENED,
                        now,
                    )
                AppControlEventType.UPDATED ->
                    updates.markConverted(campaignId, principal.userId, version, build, now)
                else -> Unit
            }
        }
    }
}

@Service
class AdminAppUpdateService(
    private val updates: AppUpdatePort,
    private val remoteConfig: AppControlRemoteConfigPort,
) : AdminAppUpdateUseCase {
    @Transactional(readOnly = true)
    override suspend fun campaigns(limit: Int, offset: Int): AdminAppUpdateCampaignPage =
        updates.campaigns(limit.coerceIn(1, 100), offset.coerceAtLeast(0))

    override suspend fun create(command: CreateAppUpdateCampaignCommand): AdminAppUpdateCampaignSummary {
        validate(command)
        val created = updates.createCampaign(
            command.copy(
                platform = command.platform.trim().lowercase(),
                targetVersion = command.targetVersion.cleanVersion(),
                targetBuild = command.targetBuild.cleanVersion(),
                appStoreUrl = command.appStoreUrl.trim(),
            ),
            Instant.now(),
        )
        return publishPolicy(created.id)
    }

    override suspend fun end(campaignId: Long): AdminAppUpdateCampaignSummary =
        updates.endCampaign(campaignId, Instant.now())
            ?.also { publishPolicy(campaignId = null) }
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "App update campaign not found.")

    @Transactional(readOnly = true)
    override suspend fun users(
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminAppUpdateUserPage = updates.campaignUsers(
        campaignId,
        query?.trim()?.takeIf(String::isNotEmpty),
        status?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
        limit.coerceIn(1, 100),
        offset.coerceAtLeast(0),
    )

    override suspend fun publishCurrentPolicy(): AdminAppUpdateCampaignSummary? {
        val active = updates.activeCampaign("ios")
        return if (active == null) {
            publishPolicy(campaignId = null)
            null
        } else {
            publishPolicy(active.id)
        }
    }

    override suspend fun activateMaintenance(command: AppControlMaintenanceCommand): AppControlMaintenanceWindow {
        validateMaintenance(command)
        val created = updates.createMaintenance(command, Instant.now())
        publishPolicy(updates.activeCampaign("ios")?.id)
        return created
    }

    override suspend fun endMaintenance(maintenanceId: Long): AppControlMaintenanceWindow {
        val ended = updates.endMaintenance(maintenanceId, Instant.now())
            ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RESOURCE_NOT_FOUND,
                "App control maintenance window not found.",
            )
        publishPolicy(updates.activeCampaign("ios")?.id)
        return ended
    }

    override suspend fun endCurrentMaintenance(): AppControlMaintenanceWindow? {
        val active = updates.activeMaintenance(Instant.now()) ?: run {
            publishPolicy(updates.activeCampaign("ios")?.id)
            return null
        }
        return endMaintenance(active.id)
    }

    private fun validate(command: CreateAppUpdateCampaignCommand) {
        val required = listOf(
            command.platform, command.targetVersion, command.targetBuild,
            command.titleKo, command.titleEn, command.titleJa,
            command.messageKo, command.messageEn, command.messageJa,
            command.appStoreUrl, command.createdBy,
        )
        if (required.any { it.isBlank() } || command.platform.trim().lowercase() != "ios") {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "App update campaign is invalid.")
        }
    }

    private fun validateMaintenance(command: AppControlMaintenanceCommand) {
        val required = listOf(
            command.titleKo, command.titleEn, command.titleJa,
            command.messageKo, command.messageEn, command.messageJa,
            command.createdBy,
        )
        if (required.any { it.isBlank() } || command.endsAt?.let { !it.isAfter(command.startsAt) } == true) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "App control maintenance window is invalid.",
            )
        }
    }

    private suspend fun publishPolicy(campaignId: Long?): AdminAppUpdateCampaignSummary {
        val now = Instant.now()
        val revision = now.toEpochMilli()
        val campaign = updates.activeCampaign("ios")
        val maintenance = updates.activeMaintenance(now)
        val disabledUpdate = AppControlUpdatePolicy(
            enabled = false,
            campaignId = null,
            mode = null,
            minimumVersion = null,
            minimumBuild = null,
            title = null,
            message = null,
            storeUrl = null,
        )
        val appStoreUpdate = campaign?.let {
            AppControlUpdatePolicy(
                enabled = true,
                campaignId = it.id,
                mode = it.mode,
                minimumVersion = it.targetVersion,
                minimumBuild = it.targetBuild,
                title = LocalizedAppControlContent(it.titleKo, it.titleEn, it.titleJa),
                message = LocalizedAppControlContent(it.messageKo, it.messageEn, it.messageJa),
                storeUrl = it.appStoreUrl,
            )
        } ?: disabledUpdate
        val maintenancePolicy = maintenance?.let {
            AppControlMaintenancePolicy(
                enabled = true,
                maintenanceId = it.id,
                startsAt = it.startsAt,
                endsAt = it.endsAt,
                title = LocalizedAppControlContent(it.titleKo, it.titleEn, it.titleJa),
                message = LocalizedAppControlContent(it.messageKo, it.messageEn, it.messageJa),
            )
        } ?: AppControlMaintenancePolicy(
            enabled = false,
            maintenanceId = null,
            startsAt = null,
            endsAt = null,
            title = null,
            message = null,
        )
        val policy = AppControlRemotePolicy(
            policyId = "ios-$revision",
            revision = revision,
            publishedAt = now,
            validUntil = now.plus(Duration.ofDays(30)),
            maintenance = maintenancePolicy,
            channels = mapOf(
                com.buddystudy.backend.appupdate.application.model.AppDistributionChannel.APP_STORE to appStoreUpdate,
                com.buddystudy.backend.appupdate.application.model.AppDistributionChannel.TESTFLIGHT to appStoreUpdate,
            ),
        )
        updates.updateRemoteConfigPublication(
            campaignId,
            RemoteConfigPublicationStatus.PENDING,
            revision,
            null,
            null,
            now,
        )
        return try {
            val result = remoteConfig.publish(policy)
            updates.updateRemoteConfigPublication(
                campaignId,
                RemoteConfigPublicationStatus.PUBLISHED,
                result.revision,
                result.publishedAt,
                null,
                Instant.now(),
            )
            campaignId?.let { id ->
                updates.campaigns(100, 0).campaigns.firstOrNull { it.id == id }
            } ?: AdminAppUpdateCampaignSummary(
                id = 0,
                platform = "ios",
                targetVersion = "",
                targetBuild = "",
                mode = AppUpdateMode.OPTIONAL,
                status = "ENDED",
                appStoreUrl = "",
                createdBy = "system",
                activatedAt = now,
                endedAt = now,
                checkedUserCount = 0,
                promptedUserCount = 0,
                openedUserCount = 0,
                convertedUserCount = 0,
                conversionRate = 0.0,
                remoteConfigStatus = RemoteConfigPublicationStatus.DISABLED,
                remoteConfigRevision = result.revision,
                remoteConfigPublishedAt = result.publishedAt,
            )
        } catch (error: Exception) {
            updates.updateRemoteConfigPublication(
                campaignId,
                RemoteConfigPublicationStatus.FAILED,
                revision,
                null,
                error.message?.take(1000),
                Instant.now(),
            )
            throw ApiException(
                HttpStatus.BAD_GATEWAY,
                ApiErrorCode.INTERNAL_SERVER_ERROR,
                "Firebase Remote Config publication failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }
}

internal data class VersionNumber(val version: String, val build: String) : Comparable<VersionNumber> {
    override fun compareTo(other: VersionNumber): Int {
        val left = version.parts()
        val right = other.version.parts()
        repeat(maxOf(left.size, right.size)) { index ->
            val compared = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (compared != 0) return compared
        }
        return build.toLongOrNull()?.compareTo(other.build.toLongOrNull() ?: 0L)
            ?: build.compareTo(other.build)
    }
}

private fun String.parts(): List<Int> = split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
private fun String.cleanVersion(): String = trim().take(64).ifBlank { "0" }
