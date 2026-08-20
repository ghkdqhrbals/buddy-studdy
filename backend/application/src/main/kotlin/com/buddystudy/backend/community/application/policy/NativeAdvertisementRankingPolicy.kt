package com.buddystudy.backend.community.application.policy

import com.buddystudy.community.domain.entity.NativeAdvertisementAudience
import com.buddystudy.community.domain.entity.NativeAdvertisementCampaignEntity
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.math.ln
import kotlin.math.sqrt

data class NativeAdvertisementCandidate(
    val campaign: NativeAdvertisementCampaignEntity,
    val userSelectionsToday: Long,
    val latestUserSelectionAt: Instant?,
    val latestUserViewAt: Instant?,
    val campaignSelections: Long,
    val campaignViews: Long,
)

data class RankedNativeAdvertisement(
    val candidate: NativeAdvertisementCandidate,
    val score: Double,
)

object NativeAdvertisementDeepLinkPolicy {
    private val supportedHosts = setOf(
        "home",
        "study",
        "studies",
        "records",
        "record",
        "history",
        "stats",
        "statistics",
        "settings",
        "profile",
        "public",
        "feedback",
    )

    fun isSupported(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("buddystudy", ignoreCase = true) &&
            uri.host?.lowercase() in supportedHosts &&
            uri.userInfo == null &&
            uri.fragment == null
    }.getOrDefault(false)
}

object NativeAdvertisementRankingPolicy {
    const val placement = "COMMUNITY_FEED"
    private const val explorationPercent = 15L
    private const val performanceWindowDays = 30L

    fun rank(
        candidates: List<NativeAdvertisementCandidate>,
        authenticated: Boolean,
        feedItemCount: Int,
        now: Instant,
    ): List<RankedNativeAdvertisement> {
        val totalSelections = candidates.sumOf { it.campaignSelections }.coerceAtLeast(0)
        return candidates.mapNotNull { candidate ->
            val campaign = candidate.campaign
            if (!isEligible(candidate, authenticated, feedItemCount, now)) {
                return@mapNotNull null
            }

            val selections = candidate.campaignSelections.coerceAtLeast(0)
            val views = candidate.campaignViews.coerceIn(0, selections)
            val smoothedViewRate = (views + 1.0) / (selections + 10.0)
            val explorationBonus = sqrt(ln(totalSelections + 2.0) / (selections + 1.0))
            val freshness = candidate.latestUserSelectionAt?.let {
                (Duration.between(it, now).seconds.toDouble() / Duration.ofDays(7).seconds)
                    .coerceIn(0.0, 1.0)
            } ?: 1.0
            val relevance = if (authenticated) {
                campaign.authenticatedRelevance.toDouble()
            } else {
                campaign.anonymousRelevance.toDouble()
            }
            val score = campaign.basePriority.toDouble() * 40.0 +
                relevance * 35.0 +
                smoothedViewRate * 20.0 +
                explorationBonus * 8.0 +
                freshness * 8.0 -
                candidate.userSelectionsToday * 12.0
            RankedNativeAdvertisement(candidate, score)
        }.sortedWith(
            compareByDescending<RankedNativeAdvertisement> { it.score }
                .thenBy { it.candidate.campaign.campaignKey }
        )
    }

    fun select(ranked: List<RankedNativeAdvertisement>, entropy: Long): RankedNativeAdvertisement? {
        if (ranked.isEmpty()) {
            return null
        }
        val pool = ranked.take(3)
        val bucket = Math.floorMod(entropy, 100L)
        val index = if (bucket < explorationPercent && pool.size > 1) {
            1 + Math.floorMod(entropy / 100L, pool.size - 1L).toInt()
        } else {
            0
        }
        return pool[index]
    }

    fun position(campaign: NativeAdvertisementCampaignEntity, feedItemCount: Int, entropy: Long): Int? {
        val lastPosition = minOf(campaign.latestPosition, feedItemCount - 1)
        if (feedItemCount < campaign.minimumFeedItemCount || lastPosition < campaign.earliestPosition) {
            return null
        }
        val count = lastPosition - campaign.earliestPosition + 1
        return campaign.earliestPosition + Math.floorMod(entropy, count.toLong()).toInt()
    }

    fun performanceWindowStart(now: Instant): Instant = now.minus(Duration.ofDays(performanceWindowDays))

    private fun isEligible(
        candidate: NativeAdvertisementCandidate,
        authenticated: Boolean,
        feedItemCount: Int,
        now: Instant,
    ): Boolean {
        val campaign = candidate.campaign
        if (feedItemCount < campaign.minimumFeedItemCount || !NativeAdvertisementDeepLinkPolicy.isSupported(campaign.deepLink)) {
            return false
        }
        if (campaign.audience == NativeAdvertisementAudience.AUTHENTICATED && !authenticated) {
            return false
        }
        if (campaign.audience == NativeAdvertisementAudience.ANONYMOUS && authenticated) {
            return false
        }
        if (candidate.userSelectionsToday >= campaign.dailySelectionCap) {
            return false
        }
        candidate.latestUserSelectionAt?.let {
            if (Duration.between(it, now).seconds < campaign.minimumSecondsBetweenSelections) {
                return false
            }
        }
        candidate.latestUserViewAt?.let {
            if (Duration.between(it, now).seconds < campaign.postViewCooldownSeconds) {
                return false
            }
        }
        return true
    }
}
