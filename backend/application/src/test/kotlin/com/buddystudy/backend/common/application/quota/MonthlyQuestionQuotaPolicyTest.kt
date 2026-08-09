package com.buddystudy.backend.common.application.quota

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class MonthlyQuestionQuotaPolicyTest {
    @Test
    fun `only a lower ranked tier is a downgrade`() {
        assertEquals(true, MonthlyQuestionQuotaPolicy.isDowngrade("TIER3", "TIER2"))
        assertEquals(false, MonthlyQuestionQuotaPolicy.isDowngrade("TIER2", "TIER3"))
        assertEquals(false, MonthlyQuestionQuotaPolicy.isDowngrade("TIER2", "TIER2"))
    }

    @Test
    fun `only a higher ranked tier is an upgrade`() {
        assertEquals(true, MonthlyQuestionQuotaPolicy.isUpgrade("TIER2", "TIER3"))
        assertEquals(false, MonthlyQuestionQuotaPolicy.isUpgrade("TIER3", "TIER2"))
        assertEquals(false, MonthlyQuestionQuotaPolicy.isUpgrade("TIER2", "TIER2"))
    }

    @Test
    fun `downgrade is deferred while the current paid period is active`() {
        val purchasedAt = Instant.parse("2032-08-09T00:00:00Z")

        assertEquals(
            true,
            MonthlyQuestionQuotaPolicy.shouldDeferUntilRenewal(
                previousTierCode = "TIER3",
                nextTierCode = "TIER2",
                currentPlanEndsAt = purchasedAt.plusSeconds(60),
                purchasedAt = purchasedAt,
            ),
        )
    }

    @Test
    fun `downgrade applies when the renewal transaction reaches the current period end`() {
        val renewalAt = Instant.parse("2032-09-09T00:00:00Z")

        assertEquals(
            false,
            MonthlyQuestionQuotaPolicy.shouldDeferUntilRenewal(
                previousTierCode = "TIER3",
                nextTierCode = "TIER2",
                currentPlanEndsAt = renewalAt,
                purchasedAt = renewalAt,
            ),
        )
    }

    @Test
    fun `downgrade remains deferred when the current period end is not authoritative`() {
        assertEquals(
            true,
            MonthlyQuestionQuotaPolicy.shouldDeferUntilRenewal(
                previousTierCode = "TIER3",
                nextTierCode = "TIER2",
                currentPlanEndsAt = null,
                purchasedAt = Instant.parse("2032-08-09T00:00:00Z"),
            ),
        )
    }

    @Test
    fun `same tier and upgrades are never treated as deferred downgrades`() {
        val purchasedAt = Instant.parse("2032-08-09T00:00:00Z")
        val currentPlanEndsAt = purchasedAt.plusSeconds(60)

        assertEquals(
            false,
            MonthlyQuestionQuotaPolicy.shouldDeferUntilRenewal(
                previousTierCode = "TIER2",
                nextTierCode = "TIER2",
                currentPlanEndsAt = currentPlanEndsAt,
                purchasedAt = purchasedAt,
            ),
        )
        assertEquals(
            false,
            MonthlyQuestionQuotaPolicy.shouldDeferUntilRenewal(
                previousTierCode = "TIER2",
                nextTierCode = "TIER3",
                currentPlanEndsAt = currentPlanEndsAt,
                purchasedAt = purchasedAt,
            ),
        )
    }
}
