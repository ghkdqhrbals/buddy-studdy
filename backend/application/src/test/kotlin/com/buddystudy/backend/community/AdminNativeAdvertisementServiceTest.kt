package com.buddystudy.backend.community

import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignCommand
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignFilter
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignStatus
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserPage
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserSummary
import com.buddystudy.backend.community.application.port.outbound.AdminNativeAdvertisementPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementCampaignPerformance
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
    fun `administrator creates active Coupang campaign with normalized key`(): Unit = runBlocking {
        val port = FakeAdminNativeAdvertisementPort()
        val service = AdminNativeAdvertisementService(port)

        val created = service.create(command(campaignKey = "  COUPANG-DESK-LAMP  "))

        assertThat(created.campaignKey).isEqualTo("coupang-desk-lamp")
        assertThat(created.destinationUrl).startsWith("https://link.coupang.com/")
        assertThat(created.imageUrl).startsWith("https://thumbnail6.coupangcdn.com/")
        assertThat(created.affiliateDisclosureKo).contains("쿠팡 파트너스")
        assertThat(port.saved.single().placement).isEqualTo("COMMUNITY_FEED")
    }

    @Test
    fun `campaign list includes durable thirty day selection performance and ranking settings`(): Unit = runBlocking {
        val port = FakeAdminNativeAdvertisementPort().apply {
            saved += command().toEntity(id = 4)
            selections = 20
            views = 5
            suppressions = 4
        }
        val service = AdminNativeAdvertisementService(port)

        val page = service.campaigns(
            query = null,
            status = null,
            audience = null,
            limit = 20,
            offset = 0,
        )

        assertThat(page.campaigns.single().performanceViewRate).isEqualTo(0.25)
        assertThat(page.campaigns.single().performanceSuppressionRate).isEqualTo(0.2)
        assertThat(page.rankingPolicy.exploitationPercent).isEqualTo(85)
        assertThat(page.rankingPolicy.explorationPercent).isEqualTo(15)
        assertThat(page.rankingPolicy.basePriorityWeight).isEqualTo(40.0)
        assertThat(page.rankingPolicy.notInterestedPenaltyWeight).isEqualTo(40.0)
    }

    @Test
    fun `campaign list normalizes filters and shares one evaluation instant across count and page`(): Unit = runBlocking {
        val port = FakeAdminNativeAdvertisementPort().apply {
            saved += command().toEntity(id = 4)
        }
        val service = AdminNativeAdvertisementService(port)

        val page = service.campaigns(
            query = "  FoCuS  ",
            status = " active ",
            audience = " authenticated ",
            limit = 500,
            offset = -3,
        )

        assertThat(port.listFilter?.query).isEqualTo("focus")
        assertThat(port.listFilter?.status).isEqualTo(AdminNativeAdvertisementCampaignStatus.ACTIVE)
        assertThat(port.listFilter?.audience).isEqualTo(NativeAdvertisementAudience.AUTHENTICATED)
        assertThat(port.countFilter).isEqualTo(port.listFilter)
        assertThat(page.limit).isEqualTo(100)
        assertThat(page.offset).isZero()
    }

    @Test
    fun `blank and unsupported campaign filters include all campaigns`(): Unit = runBlocking {
        val port = FakeAdminNativeAdvertisementPort().apply {
            saved += command().toEntity(id = 4)
        }
        val service = AdminNativeAdvertisementService(port)

        service.campaigns(
            query = "   ",
            status = "unknown",
            audience = "unknown",
            limit = 20,
            offset = 0,
        )

        assertThat(port.listFilter?.query).isNull()
        assertThat(port.listFilter?.status).isNull()
        assertThat(port.listFilter?.audience).isNull()
    }

    @Test
    fun `campaign rejects non Coupang external destination`() {
        val service = AdminNativeAdvertisementService(FakeAdminNativeAdvertisementPort())

        assertThatThrownBy {
            runBlocking { service.create(command(destinationUrl = "https://example.com/product")) }
        }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `Coupang campaign requires product image and localized affiliate disclosures`() {
        val service = AdminNativeAdvertisementService(FakeAdminNativeAdvertisementPort())

        assertThatThrownBy {
            runBlocking { service.create(command().copy(imageUrl = null)) }
        }.isInstanceOf(ApiException::class.java)
        assertThatThrownBy {
            runBlocking { service.create(command().copy(affiliateDisclosureJa = null)) }
        }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `campaign update preserves identity and performance history`(): Unit = runBlocking {
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

    @Test
    fun `campaign users normalize paging and redact anonymous and withdrawn identity fields`(): Unit = runBlocking {
        val selectedAt = Instant.parse("2026-08-01T00:00:00Z")
        val port = FakeAdminNativeAdvertisementPort().apply {
            saved += command().toEntity(id = 8)
            userRows += AdminNativeAdvertisementUserSummary(
                userId = 10,
                accountStatus = "ACTIVE",
                email = "member@example.com",
                displayName = "Member",
                selectionCount = 4,
                destinationOpenCount = 2,
                openRate = 0.5,
                distinctDeviceCount = 2,
                firstSelectedAt = selectedAt,
                lastSelectedAt = selectedAt,
                lastViewedAt = selectedAt,
            )
            userRows += AdminNativeAdvertisementUserSummary(
                userId = 11,
                accountStatus = "ANONYMOUS",
                email = "should-not-leak@example.com",
                displayName = "Buddy",
                selectionCount = 3,
                destinationOpenCount = 0,
                openRate = 0.0,
                distinctDeviceCount = 1,
                firstSelectedAt = selectedAt,
                lastSelectedAt = selectedAt,
                lastViewedAt = null,
            )
            userRows += AdminNativeAdvertisementUserSummary(
                userId = 12,
                accountStatus = "WITHDRAWN",
                email = "withdrawn@example.com",
                displayName = "Withdrawn user",
                selectionCount = 1,
                destinationOpenCount = 1,
                openRate = 1.0,
                distinctDeviceCount = 1,
                firstSelectedAt = selectedAt,
                lastSelectedAt = selectedAt,
                lastViewedAt = selectedAt,
            )
        }
        val service = AdminNativeAdvertisementService(port)

        val page = service.users(
            campaignId = 8,
            query = "  member@example.com  ",
            status = " opened ",
            limit = 500,
            offset = -10,
        )

        assertThat(port.lastUserRequest).isEqualTo(
            UserRequest(8, "member@example.com", "OPENED", 100, 0),
        )
        assertThat(page.limit).isEqualTo(100)
        assertThat(page.offset).isZero()
        assertThat(page.users[0].email).isEqualTo("member@example.com")
        assertThat(page.users[0].displayName).isEqualTo("Member")
        assertThat(page.users[1].email).isNull()
        assertThat(page.users[1].displayName).isNull()
        assertThat(page.users[2].email).isNull()
        assertThat(page.users[2].displayName).isNull()
    }

    private class FakeAdminNativeAdvertisementPort : AdminNativeAdvertisementPort {
        val saved = mutableListOf<NativeAdvertisementCampaignEntity>()
        var selections = 0L
        var views = 0L
        var suppressions = 0L
        val userRows = mutableListOf<AdminNativeAdvertisementUserSummary>()
        var lastUserRequest: UserRequest? = null
        var countFilter: AdminNativeAdvertisementCampaignFilter? = null
        var listFilter: AdminNativeAdvertisementCampaignFilter? = null

        override suspend fun countCampaigns(filter: AdminNativeAdvertisementCampaignFilter): Long {
            countFilter = filter
            return saved.size.toLong()
        }
        override suspend fun findCampaigns(
            filter: AdminNativeAdvertisementCampaignFilter,
            limit: Int,
            offset: Int,
        ): List<NativeAdvertisementCampaignEntity> {
            listFilter = filter
            return saved.drop(offset).take(limit)
        }
        override suspend fun findCampaign(id: Long) = saved.firstOrNull { it.id == id }
        override suspend fun findCampaignByKey(campaignKey: String) = saved.firstOrNull { it.campaignKey == campaignKey }
        override suspend fun saveCampaign(entity: NativeAdvertisementCampaignEntity): NativeAdvertisementCampaignEntity {
            if (entity.id == 0L) entity.id = (saved.maxOfOrNull { it.id } ?: 0L) + 1
            saved.removeAll { it.id == entity.id }
            saved += entity
            return entity
        }
        override suspend fun findCampaignPerformance(
            campaignIds: Collection<Long>,
            since: Instant,
        ): Map<Long, NativeAdvertisementCampaignPerformance> = campaignIds.associateWith { campaignId ->
            NativeAdvertisementCampaignPerformance(campaignId, selections, views, suppressions)
        }
        override suspend fun campaignUsers(
            campaignId: Long,
            query: String?,
            status: String?,
            limit: Int,
            offset: Int,
        ): AdminNativeAdvertisementUserPage {
            lastUserRequest = UserRequest(campaignId, query, status, limit, offset)
            return AdminNativeAdvertisementUserPage(userRows, userRows.size.toLong(), limit, offset)
        }
    }

    private data class UserRequest(
        val campaignId: Long,
        val query: String?,
        val status: String?,
        val limit: Int,
        val offset: Int,
    )
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
    imageUrl = "https://thumbnail6.coupangcdn.com/example.jpg",
    affiliateDisclosureKo = "이 포스팅은 쿠팡 파트너스 활동의 일환으로, 이에 따른 일정액의 수수료를 제공받습니다.",
    affiliateDisclosureEn = "This content contains Coupang Partners affiliate links, and we may receive a commission from qualifying purchases.",
    affiliateDisclosureJa = "このコンテンツはCoupang Partnersの活動の一環として、購入により一定額の手数料を受け取る場合があります。",
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
    imageUrl = imageUrl,
    affiliateDisclosureKo = affiliateDisclosureKo,
    affiliateDisclosureEn = affiliateDisclosureEn,
    affiliateDisclosureJa = affiliateDisclosureJa,
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
