package com.buddystudy.backend.community

import com.buddystudy.backend.community.application.policy.NativeAdvertisementCandidate
import com.buddystudy.backend.community.application.policy.NativeAdvertisementDeepLinkPolicy
import com.buddystudy.backend.community.application.policy.NativeAdvertisementRankingPolicy
import com.buddystudy.community.domain.entity.NativeAdvertisementCampaignEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class NativeAdvertisementRankingPolicyTest {
    private val now = Instant.parse("2026-08-20T10:00:00Z")

    @Test
    fun `ranking excludes fatigued campaigns and prefers relevant campaigns`() {
        val relevant = candidate(campaign(1, "relevant", relevance = 1.0))
        val irrelevant = candidate(campaign(2, "irrelevant", relevance = 0.1))
        val fatigued = candidate(
            campaign(3, "fatigued", relevance = 1.0, dailyCap = 2),
            userSelectionsToday = 2,
        )

        val ranked = NativeAdvertisementRankingPolicy.rank(
            candidates = listOf(irrelevant, fatigued, relevant),
            authenticated = true,
            feedItemCount = 20,
            now = now,
        )

        assertThat(ranked.map { it.candidate.campaign.campaignKey })
            .containsExactly("relevant", "irrelevant")
    }

    @Test
    fun `ranking penalizes campaigns users frequently mark not interested`() {
        val accepted = candidate(
            campaign(1, "accepted"),
            campaignSelections = 100,
            campaignViews = 20,
            campaignSuppressions = 1,
        )
        val rejected = candidate(
            campaign(2, "rejected"),
            campaignSelections = 100,
            campaignViews = 20,
            campaignSuppressions = 30,
        )

        val ranked = NativeAdvertisementRankingPolicy.rank(
            candidates = listOf(rejected, accepted),
            authenticated = true,
            feedItemCount = 20,
            now = now,
        )

        assertThat(ranked.map { it.candidate.campaign.campaignKey })
            .containsExactly("accepted", "rejected")
    }

    @Test
    fun `selection exploits top result and explores only bounded top three`() {
        val ranked = NativeAdvertisementRankingPolicy.rank(
            candidates = listOf(
                candidate(campaign(1, "first", priority = 1.0)),
                candidate(campaign(2, "second", priority = 0.8)),
                candidate(campaign(3, "third", priority = 0.6)),
                candidate(campaign(4, "fourth", priority = 0.4)),
            ),
            authenticated = true,
            feedItemCount = 20,
            now = now,
        )

        assertThat(NativeAdvertisementRankingPolicy.select(ranked, entropy = 15)?.candidate?.campaign?.campaignKey)
            .isEqualTo("first")
        assertThat(NativeAdvertisementRankingPolicy.select(ranked, entropy = 0)?.candidate?.campaign?.campaignKey)
            .isEqualTo("second")
        assertThat(NativeAdvertisementRankingPolicy.select(ranked, entropy = 100)?.candidate?.campaign?.campaignKey)
            .isEqualTo("third")
    }

    @Test
    fun `server position stays inside campaign feed bounds`() {
        val campaign = campaign(1, "bounded")

        assertThat(NativeAdvertisementRankingPolicy.position(campaign, feedItemCount = 3, entropy = 1)).isNull()
        assertThat(NativeAdvertisementRankingPolicy.position(campaign, feedItemCount = 20, entropy = 0)).isEqualTo(2)
        assertThat(NativeAdvertisementRankingPolicy.position(campaign, feedItemCount = 20, entropy = 5)).isEqualTo(7)
    }

    @Test
    fun `campaign destinations allow supported app routes and Coupang HTTPS URLs only`() {
        assertThat(NativeAdvertisementDeepLinkPolicy.isSupported("buddystudy://feedback")).isTrue()
        assertThat(NativeAdvertisementDeepLinkPolicy.isSupported("https://link.coupang.com/a/example?lptag=affiliate")).isTrue()
        assertThat(NativeAdvertisementDeepLinkPolicy.isSupported("https://www.coupang.com/vp/products/123?itemId=456")).isTrue()
        assertThat(NativeAdvertisementDeepLinkPolicy.isSupported("https://example.com")).isFalse()
        assertThat(NativeAdvertisementDeepLinkPolicy.isSupported("http://link.coupang.com/a/example")).isFalse()
        assertThat(NativeAdvertisementDeepLinkPolicy.isSupported("https://link.coupang.com.evil.example/a/example")).isFalse()
        assertThat(NativeAdvertisementDeepLinkPolicy.isSupported("https://user@link.coupang.com/a/example")).isFalse()
        assertThat(NativeAdvertisementDeepLinkPolicy.isSupported("buddystudy://unknown")).isFalse()
    }

    private fun campaign(
        id: Long,
        key: String,
        priority: Double = 0.8,
        relevance: Double = 0.8,
        dailyCap: Int = 2,
    ) = NativeAdvertisementCampaignEntity(
        id = id,
        campaignKey = key,
        titleKo = key,
        titleEn = key,
        titleJa = key,
        deepLink = "buddystudy://feedback",
        basePriority = BigDecimal.valueOf(priority),
        authenticatedRelevance = BigDecimal.valueOf(relevance),
        dailySelectionCap = dailyCap,
        minimumSecondsBetweenSelections = 0,
    )

    private fun candidate(
        campaign: NativeAdvertisementCampaignEntity,
        userSelectionsToday: Long = 0,
        campaignSelections: Long = 0,
        campaignViews: Long = 0,
        campaignSuppressions: Long = 0,
    ) = NativeAdvertisementCandidate(
        campaign = campaign,
        userSelectionsToday = userSelectionsToday,
        latestUserSelectionAt = null,
        latestUserViewAt = null,
        campaignSelections = campaignSelections,
        campaignViews = campaignViews,
        campaignSuppressions = campaignSuppressions,
    )
}
