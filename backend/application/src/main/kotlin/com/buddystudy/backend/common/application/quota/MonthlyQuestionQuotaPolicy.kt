package com.buddystudy.backend.common.application.quota

import java.time.Instant

object MonthlyQuestionQuotaPolicy {
    const val VERSION = 4

    fun tierRank(tierCode: String?): Int = when (tierCode) {
        "TIER3" -> 3
        "TIER2" -> 2
        else -> 1
    }

    fun isDowngrade(previousTierCode: String?, nextTierCode: String?): Boolean =
        tierRank(nextTierCode) < tierRank(previousTierCode)

    fun isUpgrade(previousTierCode: String?, nextTierCode: String?): Boolean =
        tierRank(nextTierCode) > tierRank(previousTierCode)

    fun shouldDeferUntilRenewal(
        previousTierCode: String?,
        nextTierCode: String?,
        currentPlanEndsAt: Instant?,
        purchasedAt: Instant,
    ): Boolean = isDowngrade(previousTierCode, nextTierCode) &&
        (currentPlanEndsAt == null || purchasedAt.isBefore(currentPlanEndsAt))
}
