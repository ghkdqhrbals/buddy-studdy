package com.buddystudy.community.domain.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant

enum class NativeAdvertisementAudience {
    ALL,
    AUTHENTICATED,
    ANONYMOUS,
}

@Table("native_ad_campaigns")
class NativeAdvertisementCampaignEntity(
    @Id
    var id: Long = 0,
    var campaignKey: String = "",
    var placement: String = "COMMUNITY_FEED",
    var audience: NativeAdvertisementAudience = NativeAdvertisementAudience.ALL,
    var disclosureKo: String = "(광고)",
    var disclosureEn: String = "(Ad)",
    var disclosureJa: String = "（広告）",
    var titleKo: String = "",
    var titleEn: String = "",
    var titleJa: String = "",
    var bodyKo: String? = null,
    var bodyEn: String? = null,
    var bodyJa: String? = null,
    var imageUrl: String? = null,
    var affiliateDisclosureKo: String? = null,
    var affiliateDisclosureEn: String? = null,
    var affiliateDisclosureJa: String? = null,
    var deepLink: String = "",
    var basePriority: BigDecimal = BigDecimal.ONE,
    var authenticatedRelevance: BigDecimal = BigDecimal.ONE,
    var anonymousRelevance: BigDecimal = BigDecimal.ONE,
    var dailySelectionCap: Int = 2,
    var minimumSecondsBetweenSelections: Int = 21_600,
    var postViewCooldownSeconds: Int = 604_800,
    var minimumFeedItemCount: Int = 4,
    var earliestPosition: Int = 2,
    var latestPosition: Int = 7,
    var active: Boolean = true,
    var startsAt: Instant? = null,
    var endsAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)

@Table("native_ad_selection_history")
class NativeAdvertisementSelectionEntity(
    @Id
    var id: Long = 0,
    var selectionId: String = "",
    var campaignId: Long = 0,
    var userId: Long = 0,
    var deviceId: String = "",
    var placement: String = "COMMUNITY_FEED",
    var language: String = "ko",
    var position: Int = 0,
    var rankScore: BigDecimal = BigDecimal.ZERO,
    var selectedAt: Instant = Instant.now(),
    var impressionAt: Instant? = null,
    var viewedAt: Instant? = null,
)
