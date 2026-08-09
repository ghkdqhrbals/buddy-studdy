package com.buddystudy.backend.common.application.quota

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonthlyQuestionQuotaPolicyTest {
    @Test
    fun `only a lower ranked tier is a downgrade`() {
        assertEquals(true, MonthlyQuestionQuotaPolicy.isDowngrade("TIER3", "TIER2"))
        assertEquals(false, MonthlyQuestionQuotaPolicy.isDowngrade("TIER2", "TIER3"))
        assertEquals(false, MonthlyQuestionQuotaPolicy.isDowngrade("TIER2", "TIER2"))
    }

    @Test
    fun `upgrade starts with no carried bonus`() {
        assertEquals(
            0,
            MonthlyQuestionQuotaPolicy.carriedBonusForTierChange(
                previousTierRank = 2,
                nextTierRank = 3,
                previousBaseLimit = 300,
                previousBonusLimit = 20,
                previousCommittedCount = 75,
            ),
        )
    }

    @Test
    fun `downgrade carries every uncommitted question from the previous tier`() {
        assertEquals(
            925,
            MonthlyQuestionQuotaPolicy.carriedBonusForTierChange(
                previousTierRank = 3,
                nextTierRank = 2,
                previousBaseLimit = 1_000,
                previousBonusLimit = 25,
                previousCommittedCount = 100,
            ),
        )
    }

    @Test
    fun `downgrade never carries consumed or negative capacity`() {
        assertEquals(
            0,
            MonthlyQuestionQuotaPolicy.carriedBonusForTierChange(
                previousTierRank = 3,
                nextTierRank = 2,
                previousBaseLimit = 1_000,
                previousBonusLimit = 0,
                previousCommittedCount = 1_001,
            ),
        )
    }
}
