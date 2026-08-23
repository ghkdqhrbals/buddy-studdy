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

    private val supportedCoupangHosts = setOf(
        "coupang.com",
        "www.coupang.com",
        "link.coupang.com",
    )

    fun isSupported(value: String): Boolean = runCatching {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        uri.userInfo == null && uri.fragment == null && when (scheme) {
            "buddystudy" -> host in supportedHosts && uri.port == -1
            "https" -> host in supportedCoupangHosts && uri.port in setOf(-1, 443) && !uri.path.isNullOrBlank()
            else -> false
        }
    }.getOrDefault(false)

    fun isCoupang(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() == "https" &&
            uri.host?.lowercase() in supportedCoupangHosts &&
            uri.userInfo == null &&
            uri.fragment == null &&
            uri.port in setOf(-1, 443) &&
            !uri.path.isNullOrBlank()
    }.getOrDefault(false)

    fun providerName(value: String): String = if (isCoupang(value)) "쿠팡" else "BuddyStudy"
}

object NativeAdvertisementImagePolicy {
    fun isSupported(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme?.lowercase() == "https" &&
            (host == "coupangcdn.com" || host.endsWith(".coupangcdn.com")) &&
            uri.userInfo == null &&
            uri.fragment == null &&
            uri.port in setOf(-1, 443) &&
            !uri.path.isNullOrBlank()
    }.getOrDefault(false)
}

object NativeAdvertisementRankingPolicy {
    const val placement = "COMMUNITY_FEED"
    const val explorationPercent = 15L
    const val performanceWindowDays = 30L
    const val selectionPoolSize = 3
    const val basePriorityWeight = 40.0
    const val relevanceWeight = 35.0
    const val viewRateWeight = 20.0
    const val explorationWeight = 8.0
    const val freshnessWeight = 8.0
    const val dailySelectionPenalty = 12.0

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
            val score = campaign.basePriority.toDouble() * basePriorityWeight +
                relevance * relevanceWeight +
                smoothedViewRate * viewRateWeight +
                explorationBonus * explorationWeight +
                freshness * freshnessWeight -
                candidate.userSelectionsToday * dailySelectionPenalty
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
        val pool = ranked.take(selectionPoolSize)
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
