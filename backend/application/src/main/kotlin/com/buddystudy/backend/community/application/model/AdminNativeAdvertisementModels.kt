package com.buddystudy.backend.community.application.model

import com.buddystudy.backend.common.application.model.PageResponse
import com.buddystudy.community.domain.entity.NativeAdvertisementAudience
import java.math.BigDecimal
import java.time.Instant

data class AdminNativeAdvertisementCampaignCommand(
    val campaignKey: String,
    val audience: NativeAdvertisementAudience,
    val disclosureKo: String,
    val disclosureEn: String,
    val disclosureJa: String,
    val titleKo: String,
    val titleEn: String,
    val titleJa: String,
    val bodyKo: String?,
    val bodyEn: String?,
    val bodyJa: String?,
    val imageUrl: String? = null,
    val affiliateDisclosureKo: String? = null,
    val affiliateDisclosureEn: String? = null,
    val affiliateDisclosureJa: String? = null,
    val destinationUrl: String,
    val basePriority: BigDecimal,
    val authenticatedRelevance: BigDecimal,
    val anonymousRelevance: BigDecimal,
    val dailySelectionCap: Int,
    val minimumSecondsBetweenSelections: Int,
    val postViewCooldownSeconds: Int,
    val minimumFeedItemCount: Int,
    val earliestPosition: Int,
    val latestPosition: Int,
    val active: Boolean,
    val startsAt: Instant?,
    val endsAt: Instant?,
)

enum class AdminNativeAdvertisementCampaignStatus {
    ACTIVE,
    PAUSED,
    SCHEDULED,
    ENDED,
}

data class AdminNativeAdvertisementCampaignFilter(
    val query: String?,
    val status: AdminNativeAdvertisementCampaignStatus?,
    val audience: NativeAdvertisementAudience?,
    val evaluatedAt: Instant,
)

data class AdminNativeAdvertisementCampaignSummary(
    val id: Long,
    val campaignKey: String,
    val placement: String,
    val audience: NativeAdvertisementAudience,
    val disclosureKo: String,
    val disclosureEn: String,
    val disclosureJa: String,
    val titleKo: String,
    val titleEn: String,
    val titleJa: String,
    val bodyKo: String?,
    val bodyEn: String?,
    val bodyJa: String?,
    val imageUrl: String?,
    val affiliateDisclosureKo: String?,
    val affiliateDisclosureEn: String?,
    val affiliateDisclosureJa: String?,
    val destinationUrl: String,
    val basePriority: BigDecimal,
    val authenticatedRelevance: BigDecimal,
    val anonymousRelevance: BigDecimal,
    val dailySelectionCap: Int,
    val minimumSecondsBetweenSelections: Int,
    val postViewCooldownSeconds: Int,
    val minimumFeedItemCount: Int,
    val earliestPosition: Int,
    val latestPosition: Int,
    val active: Boolean,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val performanceSelections: Long,
    val performanceImpressions: Long = 0,
    val performanceImpressionRate: Double = 0.0,
    val performanceViews: Long,
    val performanceViewRate: Double,
    val performanceViewableOpenRate: Double = 0.0,
    val performanceSuppressions: Long,
    val performanceSuppressionRate: Double,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminNativeAdvertisementRankingPolicySummary(
    val performanceWindowDays: Long,
    val exploitationPercent: Long,
    val explorationPercent: Long,
    val selectionPoolSize: Int,
    val basePriorityWeight: Double,
    val relevanceWeight: Double,
    val smoothedViewRateWeight: Double,
    val explorationWeight: Double,
    val freshnessWeight: Double,
    val dailySelectionPenalty: Double,
    val notInterestedPenaltyWeight: Double = 0.0,
)

data class AdminNativeAdvertisementCampaignPage(
    val campaigns: List<AdminNativeAdvertisementCampaignSummary>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
    val rankingPolicy: AdminNativeAdvertisementRankingPolicySummary,
)

data class AdminNativeAdvertisementUserSummary(
    val userId: Long,
    val accountStatus: String,
    val email: String?,
    val displayName: String?,
    val selectionCount: Long,
    val impressionCount: Long = 0,
    val impressionRate: Double = 0.0,
    val destinationOpenCount: Long,
    val openRate: Double,
    val viewableOpenRate: Double = 0.0,
    val distinctDeviceCount: Long,
    val firstSelectedAt: Instant,
    val lastSelectedAt: Instant,
    val lastViewedAt: Instant?,
)

data class AdminNativeAdvertisementUserPage(
    val users: List<AdminNativeAdvertisementUserSummary>,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
) : PageResponse
