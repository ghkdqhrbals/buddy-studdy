package com.buddystudy.backend.community.application.model

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
    val performanceViews: Long,
    val performanceViewRate: Double,
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
)

data class AdminNativeAdvertisementCampaignPage(
    val campaigns: List<AdminNativeAdvertisementCampaignSummary>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
    val rankingPolicy: AdminNativeAdvertisementRankingPolicySummary,
)
