package com.buddystudy.backend.appupdate.application.service

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
import com.buddystudy.backend.appupdate.application.port.outbound.AppUpdatePort
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
}

@Service
class AdminAppUpdateService(
    private val updates: AppUpdatePort,
) : AdminAppUpdateUseCase {
    @Transactional(readOnly = true)
    override suspend fun campaigns(limit: Int, offset: Int): AdminAppUpdateCampaignPage =
        updates.campaigns(limit.coerceIn(1, 100), offset.coerceAtLeast(0))

    @Transactional
    override suspend fun create(command: CreateAppUpdateCampaignCommand): AdminAppUpdateCampaignSummary {
        validate(command)
        return updates.createCampaign(
            command.copy(
                platform = command.platform.trim().lowercase(),
                targetVersion = command.targetVersion.cleanVersion(),
                targetBuild = command.targetBuild.cleanVersion(),
                appStoreUrl = command.appStoreUrl.trim(),
            ),
            Instant.now(),
        )
    }

    @Transactional
    override suspend fun end(campaignId: Long): AdminAppUpdateCampaignSummary =
        updates.endCampaign(campaignId, Instant.now())
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
