package com.buddystudy.backend.appupdate.application.model

import com.buddystudy.backend.common.application.model.PageResponse
import java.time.Instant

enum class AppUpdateMode { FORCE, OPTIONAL }
enum class AppUpdateEvent { SHOWN, DISMISSED, APP_STORE_OPENED }

data class AppUpdateCheckCommand(
    val platform: String,
    val currentVersion: String,
    val currentBuild: String,
    val language: String,
)

data class AppUpdateDecision(
    val updateAvailable: Boolean,
    val shouldPresent: Boolean,
    val campaignId: Long? = null,
    val mode: AppUpdateMode? = null,
    val targetVersion: String? = null,
    val targetBuild: String? = null,
    val title: String? = null,
    val message: String? = null,
    val appStoreUrl: String? = null,
)

data class CreateAppUpdateCampaignCommand(
    val platform: String,
    val targetVersion: String,
    val targetBuild: String,
    val mode: AppUpdateMode,
    val titleKo: String,
    val titleEn: String,
    val titleJa: String,
    val messageKo: String,
    val messageEn: String,
    val messageJa: String,
    val appStoreUrl: String,
    val createdBy: String,
)

data class AppUpdateCampaign(
    val id: Long,
    val platform: String,
    val targetVersion: String,
    val targetBuild: String,
    val mode: AppUpdateMode,
    val titleKo: String,
    val titleEn: String,
    val titleJa: String,
    val messageKo: String,
    val messageEn: String,
    val messageJa: String,
    val appStoreUrl: String,
    val status: String,
    val createdBy: String,
    val activatedAt: Instant,
    val endedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun localizedTitle(language: String): String = when (language) {
        "en" -> titleEn
        "ja" -> titleJa
        else -> titleKo
    }

    fun localizedMessage(language: String): String = when (language) {
        "en" -> messageEn
        "ja" -> messageJa
        else -> messageKo
    }
}

data class AppUpdateUserState(
    val campaignId: Long,
    val userId: Long,
    val deviceId: String,
    val firstVersion: String,
    val firstBuild: String,
    val currentVersion: String,
    val currentBuild: String,
    val firstCheckedAt: Instant,
    val lastCheckedAt: Instant,
    val promptedAt: Instant?,
    val dismissedAt: Instant?,
    val appStoreOpenedAt: Instant?,
    val convertedAt: Instant?,
)

data class AdminAppUpdateCampaignSummary(
    val id: Long,
    val platform: String,
    val targetVersion: String,
    val targetBuild: String,
    val mode: AppUpdateMode,
    val status: String,
    val appStoreUrl: String,
    val createdBy: String,
    val activatedAt: Instant,
    val endedAt: Instant?,
    val checkedUserCount: Long,
    val promptedUserCount: Long,
    val openedUserCount: Long,
    val convertedUserCount: Long,
    val conversionRate: Double,
)

data class AdminAppUpdateCampaignPage(
    val campaigns: List<AdminAppUpdateCampaignSummary>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
) : PageResponse

data class AdminAppUpdateUserSummary(
    val userId: Long,
    val email: String,
    val displayName: String,
    val deviceId: String,
    val firstVersion: String,
    val firstBuild: String,
    val currentVersion: String,
    val currentBuild: String,
    val firstCheckedAt: Instant,
    val lastCheckedAt: Instant,
    val promptedAt: Instant?,
    val dismissedAt: Instant?,
    val appStoreOpenedAt: Instant?,
    val convertedAt: Instant?,
    val status: String,
)

data class AdminAppUpdateUserPage(
    val users: List<AdminAppUpdateUserSummary>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
) : PageResponse
