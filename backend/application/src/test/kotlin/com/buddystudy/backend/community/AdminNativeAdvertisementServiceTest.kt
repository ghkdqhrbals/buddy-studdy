package com.buddystudy.backend.community

import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignCommand
import com.buddystudy.backend.community.application.port.outbound.AdminNativeAdvertisementPort
import com.buddystudy.backend.community.application.service.AdminNativeAdvertisementService
import com.buddystudy.community.domain.entity.NativeAdvertisementAudience
import com.buddystudy.community.domain.entity.NativeAdvertisementCampaignEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class AdminNativeAdvertisementServiceTest {
    @Test
    fun `administrator creates active Coupang campaign with normalized key`() = runBlocking {
        val port = FakeAdminNativeAdvertisementPort()
        val service = AdminNativeAdvertisementService(port)

        val created = service.create(command(campaignKey = "  COUPANG-DESK-LAMP  "))

        assertThat(created.campaignKey).isEqualTo("coupang-desk-lamp")
        assertThat(created.destinationUrl).startsWith("https://link.coupang.com/")
        assertThat(port.saved.single().placement).isEqualTo("COMMUNITY_FEED")
    }

    @Test
    fun `campaign list includes durable thirty day selection performance and ranking settings`() = runBlocking {
        val port = FakeAdminNativeAdvertisementPort().apply {
            saved += command().toEntity(id = 4)
            selections = 20
            views = 5
        }
        val service = AdminNativeAdvertisementService(port)

        val page = service.campaigns(limit = 20, offset = 0)

        assertThat(page.campaigns.single().performanceViewRate).isEqualTo(0.25)
        assertThat(page.rankingPolicy.exploitationPercent).isEqualTo(85)
        assertThat(page.rankingPolicy.explorationPercent).isEqualTo(15)
        assertThat(page.rankingPolicy.basePriorityWeight).isEqualTo(40.0)
    }

    @Test
    fun `campaign rejects non Coupang external destination`() {
        val service = AdminNativeAdvertisementService(FakeAdminNativeAdvertisementPort())

        assertThatThrownBy {
            runBlocking { service.create(command(destinationUrl = "https://example.com/product")) }
        }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `campaign update preserves identity and performance history`() = runBlocking {
        val port = FakeAdminNativeAdvertisementPort().apply {
            saved += command().toEntity(id = 8)
            selections = 12
            views = 3
        }
        val service = AdminNativeAdvertisementService(port)

        val updated = service.update(8, command().copy(active = false, basePriority = BigDecimal("2.5")))

        assertThat(updated.id).isEqualTo(8)
        assertThat(updated.active).isFalse()
        assertThat(updated.basePriority).isEqualByComparingTo("2.5")
        assertThat(updated.performanceSelections).isEqualTo(12)
    }

    private class FakeAdminNativeAdvertisementPort : AdminNativeAdvertisementPort {
        val saved = mutableListOf<NativeAdvertisementCampaignEntity>()
        var selections = 0L
        var views = 0L

        override suspend fun countCampaigns() = saved.size.toLong()
        override suspend fun findCampaigns(limit: Int, offset: Int) = saved.drop(offset).take(limit)
        override suspend fun findCampaign(id: Long) = saved.firstOrNull { it.id == id }
        override suspend fun findCampaignByKey(campaignKey: String) = saved.firstOrNull { it.campaignKey == campaignKey }
        override suspend fun saveCampaign(entity: NativeAdvertisementCampaignEntity): NativeAdvertisementCampaignEntity {
            if (entity.id == 0L) entity.id = (saved.maxOfOrNull { it.id } ?: 0L) + 1
            saved.removeAll { it.id == entity.id }
            saved += entity
            return entity
        }
        override suspend fun countSelectionsSince(campaignId: Long, since: Instant) = selections
        override suspend fun countViewsSince(campaignId: Long, since: Instant) = views
    }
}

private fun command(
    campaignKey: String = "coupang-desk-lamp",
    destinationUrl: String = "https://link.coupang.com/a/example?lptag=affiliate",
) = AdminNativeAdvertisementCampaignCommand(
    campaignKey = campaignKey,
    audience = NativeAdvertisementAudience.ALL,
    disclosureKo = "(광고)",
    disclosureEn = "(Ad)",
    disclosureJa = "（広告）",
    titleKo = "집중을 돕는 조명",
    titleEn = "A light for focused study",
    titleJa = "集中を助けるライト",
    bodyKo = "학습 공간을 정돈해 보세요",
    bodyEn = "Improve your study space",
    bodyJa = "学習スペースを整えましょう",
    destinationUrl = destinationUrl,
    basePriority = BigDecimal.ONE,
    authenticatedRelevance = BigDecimal.ONE,
    anonymousRelevance = BigDecimal("0.5"),
    dailySelectionCap = 2,
    minimumSecondsBetweenSelections = 21_600,
    postViewCooldownSeconds = 604_800,
    minimumFeedItemCount = 4,
    earliestPosition = 2,
    latestPosition = 7,
    active = true,
    startsAt = null,
    endsAt = null,
)

private fun AdminNativeAdvertisementCampaignCommand.toEntity(id: Long) = NativeAdvertisementCampaignEntity(
    id = id,
    campaignKey = campaignKey,
    audience = audience,
    disclosureKo = disclosureKo,
    disclosureEn = disclosureEn,
    disclosureJa = disclosureJa,
    titleKo = titleKo,
    titleEn = titleEn,
    titleJa = titleJa,
    bodyKo = bodyKo,
    bodyEn = bodyEn,
    bodyJa = bodyJa,
    deepLink = destinationUrl,
    basePriority = basePriority,
    authenticatedRelevance = authenticatedRelevance,
    anonymousRelevance = anonymousRelevance,
    dailySelectionCap = dailySelectionCap,
    minimumSecondsBetweenSelections = minimumSecondsBetweenSelections,
    postViewCooldownSeconds = postViewCooldownSeconds,
    minimumFeedItemCount = minimumFeedItemCount,
    earliestPosition = earliestPosition,
    latestPosition = latestPosition,
    active = active,
    startsAt = startsAt,
    endsAt = endsAt,
)
