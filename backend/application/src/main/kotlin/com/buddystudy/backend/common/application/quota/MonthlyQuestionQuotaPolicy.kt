package com.buddystudy.backend.common.application.quota

object MonthlyQuestionQuotaPolicy {
    const val VERSION = 3

    fun tierRank(tierCode: String?): Int = when (tierCode) {
        "TIER3" -> 3
        "TIER2" -> 2
        else -> 1
    }

    fun isDowngrade(previousTierCode: String?, nextTierCode: String?): Boolean =
        tierRank(nextTierCode) < tierRank(previousTierCode)

    fun carriedBonusForTierChange(
        previousTierRank: Int,
        nextTierRank: Int,
        previousBaseLimit: Int,
        previousBonusLimit: Int,
        previousCommittedCount: Int,
    ): Int {
        if (nextTierRank >= previousTierRank) return 0

        return (previousBaseLimit.toLong() + previousBonusLimit - previousCommittedCount)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
    }
}
