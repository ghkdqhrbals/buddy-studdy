package com.buddystudy.backend.community.application.policy

import com.buddystudy.community.domain.entity.NativeAdPlacementPolicyEntity
import java.time.Instant

object NativeAdPlacementPolicy {
    const val communityFeed = "COMMUNITY_FEED"
    const val minimumFeedItemCount = 4
    const val earliestPosition = 2
    const val minimumSecondsBetweenDeliveries = 60
    const val maximumSecondsBetweenDeliveries = 2_592_000
    const val metricsWindowDays = 30L

    fun isValidMinimumSecondsBetweenDeliveries(value: Int): Boolean =
        value == 0 || value in minimumSecondsBetweenDeliveries..maximumSecondsBetweenDeliveries

    fun position(
        policy: NativeAdPlacementPolicyEntity,
        feedItemCount: Int,
        now: Instant,
        entropy: Long,
    ): Int? {
        if (!policy.enabled || policy.placement != communityFeed) return null
        if (policy.startsAt?.isAfter(now) == true || policy.endsAt?.let { !it.isAfter(now) } == true) return null
        if (feedItemCount < maxOf(minimumFeedItemCount, policy.minimumFeedItemCount)) return null
        val first = maxOf(earliestPosition, policy.earliestPosition)
        val last = minOf(policy.latestPosition, feedItemCount - 1)
        if (last < first) return null
        return first + Math.floorMod(entropy, (last - first + 1).toLong()).toInt()
    }
}
