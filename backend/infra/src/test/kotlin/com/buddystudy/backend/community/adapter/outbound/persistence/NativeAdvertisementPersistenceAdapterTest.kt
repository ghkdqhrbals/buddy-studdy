package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignFilter
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignStatus
import com.buddystudy.community.domain.entity.NativeAdvertisementAudience
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant

class NativeAdvertisementPersistenceAdapterTest {
    private val database = DatabaseClient.create(
        ConnectionFactories.get(
            "r2dbc:h2:mem:///native-ad-admin-users;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        ),
    )
    private val adapter = NativeAdvertisementPersistenceAdapter(
        campaigns = mock(NativeAdvertisementCampaignRepository::class.java),
        selections = mock(NativeAdvertisementSelectionRepository::class.java),
        placementPolicies = mock(NativeAdPlacementPolicyRepository::class.java),
        nativeAdSlots = mock(NativeAdSlotRepository::class.java),
        database = database,
    )

    @BeforeEach
    fun setUp() {
        runBlocking {
            execute("drop table if exists native_ad_campaign_suppressions")
            execute("drop table if exists native_ad_selection_history")
            execute("drop table if exists native_ad_slots")
            execute("drop table if exists native_ad_campaigns")
            execute("drop table if exists user_memberships")
            execute("drop table if exists user_entitlement_projection")
            execute("drop table if exists user_membership_tiers")
            execute("drop table if exists users")
            execute(
                """
                create table users (
                    id bigint primary key,
                    status varchar(24) not null,
                    email varchar(320) not null,
                    display_name varchar(120) not null
                )
                """.trimIndent(),
            )
            execute(
                """
                create table user_membership_tiers (
                    tier_code varchar(64) primary key,
                    monthly_question_limit int not null,
                    ad_free bigint not null
                )
                """.trimIndent(),
            )
            execute(
                """
                create table user_entitlement_projection (
                    user_id bigint primary key,
                    tier_code varchar(64) not null,
                    source varchar(32) not null,
                    access_status varchar(32) not null,
                    expires_at timestamp null,
                    projected_at timestamp not null
                )
                """.trimIndent(),
            )
            execute(
                """
                create table user_memberships (
                    id bigint auto_increment primary key,
                    user_id bigint not null,
                    tier varchar(64) not null,
                    monthly_question_limit_override int null,
                    status varchar(32) not null,
                    started_at timestamp not null,
                    expires_at timestamp null,
                    updated_at timestamp not null
                )
                """.trimIndent(),
            )
            execute(
                """
                create table native_ad_campaigns (
                    id bigint primary key,
                    campaign_key varchar(96) not null,
                    placement varchar(48) not null default 'COMMUNITY_FEED',
                    audience varchar(24) not null,
                    disclosure_ko varchar(32) not null default '(광고)',
                    disclosure_en varchar(32) not null default '(Ad)',
                    disclosure_ja varchar(32) not null default '（広告）',
                    title_ko varchar(255) not null,
                    title_en varchar(255) not null,
                    title_ja varchar(255) not null,
                    body_ko varchar(500) null,
                    body_en varchar(500) null,
                    body_ja varchar(500) null,
                    image_url varchar(1024) null,
                    affiliate_disclosure_ko varchar(500) null,
                    affiliate_disclosure_en varchar(500) null,
                    affiliate_disclosure_ja varchar(500) null,
                    deep_link varchar(512) not null default 'buddystudy://feedback',
                    base_priority decimal(8,4) not null default 1,
                    authenticated_relevance decimal(8,4) not null default 1,
                    anonymous_relevance decimal(8,4) not null default 1,
                    daily_selection_cap int not null default 2,
                    minimum_seconds_between_selections int not null default 21600,
                    post_view_cooldown_seconds int not null default 604800,
                    minimum_feed_item_count int not null default 4,
                    earliest_position int not null default 2,
                    latest_position int not null default 7,
                    active boolean not null,
                    starts_at timestamp null,
                    ends_at timestamp null,
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """.trimIndent(),
            )
            execute(
                """
                create table native_ad_campaign_suppressions (
                    id bigint auto_increment primary key,
                    campaign_id bigint not null,
                    user_id bigint not null,
                    created_at timestamp not null,
                    unique (user_id, campaign_id)
                )
                """.trimIndent(),
            )
            execute(
                """
                create table native_ad_slots (
                    id bigint auto_increment primary key,
                    slot_id varchar(36) not null unique,
                    user_id bigint not null,
                    device_id varchar(255) not null,
                    placement varchar(48) not null,
                    language varchar(8) not null,
                    position int not null,
                    feed_item_count int not null,
                    delivered_at timestamp not null,
                    ad_mob_impression_at timestamp null,
                    ad_mob_click_at timestamp null
                )
                """.trimIndent(),
            )
            execute(
                """
                create table native_ad_selection_history (
                    id bigint auto_increment primary key,
                    selection_id varchar(36) null,
                    campaign_id bigint not null,
                    user_id bigint not null,
                    device_id varchar(255) not null,
                    placement varchar(48) not null default 'COMMUNITY_FEED',
                    selected_at timestamp not null,
                    impression_at timestamp null,
                    viewed_at timestamp null,
                    native_ad_slot_id varchar(36) null
                )
                """.trimIndent(),
            )
            execute(
                """
                insert into native_ad_campaigns
                    (id, campaign_key, audience, title_ko, title_en, title_ja,
                     active, starts_at, ends_at, created_at, updated_at)
                values
                    (1, 'desk-lamp', 'ALL', '집중 조명', 'Focus Lamp', '集中ライト',
                     true, null, null, timestamp '2026-08-01 00:00:00', timestamp '2026-08-01 00:00:00'),
                    (2, 'future-plan', 'AUTHENTICATED', '예약 캠페인', 'Scheduled Study', '予約学習',
                     true, timestamp '2026-08-11 00:00:00', null, timestamp '2026-08-02 00:00:00', timestamp '2026-08-02 00:00:00'),
                    (3, 'ended-plan', 'ANONYMOUS', '종료 캠페인', 'Completed Study', '終了学習',
                     true, timestamp '2026-08-01 00:00:00', timestamp '2026-08-10 00:00:00', timestamp '2026-08-03 00:00:00', timestamp '2026-08-03 00:00:00'),
                    (4, 'paused-plan', 'ALL', '중지 캠페인', 'Paused Study', '停止学習',
                     false, timestamp '2026-08-12 00:00:00', timestamp '2026-08-20 00:00:00', timestamp '2026-08-04 00:00:00', timestamp '2026-08-04 00:00:00'),
                    (5, 'future-ended-plan', 'AUTHENTICATED', '우선순위 검증', 'Study Paradox', '優先順位',
                     true, timestamp '2026-08-12 00:00:00', timestamp '2026-08-09 00:00:00', timestamp '2026-08-05 00:00:00', timestamp '2026-08-05 00:00:00'),
                    (6, 'boundary-active', 'AUTHENTICATED', '경계 캠페인', 'Study Boundary', '境界学習',
                     true, timestamp '2026-08-10 00:00:00', timestamp '2026-08-11 00:00:00', timestamp '2026-08-06 00:00:00', timestamp '2026-08-06 00:00:00'),
                    (7, 'member-active', 'AUTHENTICATED', '회원 캠페인', 'Focused Study', '会員学習',
                     true, null, null, timestamp '2026-08-07 00:00:00', timestamp '2026-08-07 00:00:00')
                """.trimIndent(),
            )
            execute(
                """
                insert into users (id, status, email, display_name) values
                    (10, 'ACTIVE', 'member@example.com', 'Member'),
                    (11, 'ANONYMOUS', '', 'Buddy'),
                    (12, 'ACTIVE', 'other@example.com', 'Other')
                """.trimIndent(),
            )
            execute(
                "insert into user_membership_tiers (tier_code, monthly_question_limit, ad_free) " +
                    "values ('TIER1', 30, 0), ('TIER2', 300, 1), ('TIER3', 1000, 1)",
            )
            execute(
                """
                insert into user_entitlement_projection (
                    user_id, tier_code, source, access_status, expires_at, projected_at
                ) values
                    (10, 'TIER2', 'APP_STORE', 'ACTIVE', null, timestamp '2026-08-01 00:00:00'),
                    (12, 'TIER1', 'FREE', 'ACTIVE', null, timestamp '2026-08-01 00:00:00')
                """.trimIndent(),
            )
            execute(
                """
                insert into native_ad_selection_history
                    (campaign_id, user_id, device_id, selected_at, impression_at, viewed_at)
                values
                    (7, 10, 'device-a', timestamp '2026-08-01 00:00:00', timestamp '2026-08-01 00:00:01', timestamp '2026-08-01 00:05:00'),
                    (7, 10, 'device-a', timestamp '2026-08-02 00:00:00', timestamp '2026-08-02 00:00:01', null),
                    (7, 10, 'device-b', timestamp '2026-08-03 00:00:00', null, timestamp '2026-08-03 00:05:00'),
                    (7, 11, 'anonymous-device', timestamp '2026-08-04 00:00:00', null, null),
                    (8, 12, 'other-device', timestamp '2026-08-05 00:00:00', timestamp '2026-08-05 00:00:01', timestamp '2026-08-05 00:05:00')
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `ad eligibility includes anonymous users and fails closed for unresolved active accounts`(): Unit = runBlocking {
        assertThat(adapter.isAdFree(11)).isFalse()
        assertThat(adapter.isAdFree(10)).isTrue()
        assertThat(adapter.isAdFree(12)).isFalse()
        assertThat(adapter.isAdFree(999)).isNull()
    }

    @Test
    fun `active referral tier outranks the free entitlement projection for ad eligibility`(): Unit = runBlocking {
        execute(
            """
            insert into user_memberships (
                user_id, tier, monthly_question_limit_override, status, started_at, expires_at, updated_at
            ) values (
                12, 'TIER2', null, 'ACTIVE',
                timestamp '2026-08-01 00:00:00', timestamp '2099-09-01 00:00:00',
                timestamp '2026-08-01 00:00:00'
            )
            """.trimIndent(),
        )

        assertThat(adapter.isAdFree(12)).isTrue()
    }

    @Test
    fun `placement metrics use each delivery selection and event timestamp for the thirty day window`(): Unit = runBlocking {
        val since = Instant.parse("2026-07-26T00:00:00Z")
        execute(
            """
            insert into native_ad_slots (
                slot_id, user_id, device_id, placement, language, position, feed_item_count,
                delivered_at, ad_mob_impression_at, ad_mob_click_at
            ) values
                ('slot-old-delivery', 10, 'device-a', 'COMMUNITY_FEED', 'ko', 2, 4,
                 timestamp '2026-07-25 00:00:00', timestamp '2026-07-27 00:00:00', timestamp '2026-07-27 01:00:00'),
                ('slot-recent-delivery', 10, 'device-a', 'COMMUNITY_FEED', 'ko', 2, 4,
                 timestamp '2026-07-27 00:00:00', timestamp '2026-07-25 00:00:00', timestamp '2026-07-25 01:00:00')
            """.trimIndent(),
        )
        execute(
            """
            insert into native_ad_selection_history (
                selection_id, campaign_id, user_id, device_id, placement, selected_at,
                impression_at, viewed_at, native_ad_slot_id
            ) values
                ('fallback-old-selection', 7, 10, 'device-a', 'COMMUNITY_FEED',
                 timestamp '2026-07-25 00:00:00', timestamp '2026-07-27 00:00:00',
                 timestamp '2026-07-27 01:00:00', 'slot-old-delivery'),
                ('fallback-recent-selection', 7, 10, 'device-a', 'COMMUNITY_FEED',
                 timestamp '2026-07-27 00:00:00', timestamp '2026-07-25 00:00:00',
                 timestamp '2026-07-25 01:00:00', 'slot-recent-delivery')
            """.trimIndent(),
        )

        val metrics = adapter.placementMetrics("COMMUNITY_FEED", since)

        assertThat(metrics.slotDeliveries).isEqualTo(1)
        assertThat(metrics.adMobImpressions).isEqualTo(1)
        assertThat(metrics.adMobClicks).isEqualTo(1)
        assertThat(metrics.fallbackSelections).isEqualTo(1)
        assertThat(metrics.fallbackImpressions).isEqualTo(1)
        assertThat(metrics.fallbackOpens).isEqualTo(1)
    }

    @Test
    fun `campaign query searches key and localized titles without case sensitivity`(): Unit = runBlocking {
        val byKey = adapter.findCampaigns(filter(query = "desk"), limit = 20, offset = 0)
        val byKorean = adapter.findCampaigns(filter(query = "집중"), limit = 20, offset = 0)
        val byEnglish = adapter.findCampaigns(filter(query = "FoCuS LaMp"), limit = 20, offset = 0)
        val byJapanese = adapter.findCampaigns(filter(query = "集中"), limit = 20, offset = 0)

        assertThat(byKey).extracting<Long> { it.id }.containsExactly(1)
        assertThat(byKorean).extracting<Long> { it.id }.containsExactly(1)
        assertThat(byEnglish).extracting<Long> { it.id }.containsExactly(1)
        assertThat(byJapanese).extracting<Long> { it.id }.containsExactly(1)
    }

    @Test
    fun `campaign status follows paused scheduled ended active UI precedence at one instant`(): Unit = runBlocking {
        val expected = mapOf(
            AdminNativeAdvertisementCampaignStatus.ACTIVE to setOf(1L, 6L, 7L),
            AdminNativeAdvertisementCampaignStatus.PAUSED to setOf(4L),
            AdminNativeAdvertisementCampaignStatus.SCHEDULED to setOf(2L, 5L),
            AdminNativeAdvertisementCampaignStatus.ENDED to setOf(3L),
        )

        expected.forEach { (status, ids) ->
            val campaignFilter = filter(status = status)
            val campaigns = adapter.findCampaigns(campaignFilter, limit = 20, offset = 0)

            assertThat(campaigns.map { it.id }).containsExactlyInAnyOrderElementsOf(ids)
            assertThat(adapter.countCampaigns(campaignFilter)).isEqualTo(ids.size.toLong())
        }
    }

    @Test
    fun `campaign audience is exact and count matches filtered page criteria`(): Unit = runBlocking {
        val audienceOnly = filter(audience = NativeAdvertisementAudience.ALL)
        val combined = filter(
            query = "study",
            status = AdminNativeAdvertisementCampaignStatus.ACTIVE,
            audience = NativeAdvertisementAudience.AUTHENTICATED,
        )

        val allAudience = adapter.findCampaigns(audienceOnly, limit = 20, offset = 0)
        val secondPageItem = adapter.findCampaigns(combined, limit = 1, offset = 1)

        assertThat(allAudience.map { it.id }).containsExactlyInAnyOrder(1L, 4L)
        assertThat(adapter.countCampaigns(audienceOnly)).isEqualTo(2)
        assertThat(adapter.countCampaigns(combined)).isEqualTo(2)
        assertThat(secondPageItem).extracting<Long> { it.id }.containsExactly(6)
    }

    @Test
    fun `campaign users aggregate deliveries opens devices and timestamps`(): Unit = runBlocking {
        val page = adapter.campaignUsers(7, query = null, status = null, limit = 20, offset = 0)

        assertThat(page.totalCount).isEqualTo(2)
        val users = page.users.associateBy { it.userId }
        assertThat(users.getValue(10).selectionCount).isEqualTo(3)
        assertThat(users.getValue(10).impressionCount).isEqualTo(2)
        assertThat(users.getValue(10).impressionRate).isEqualTo(2.0 / 3.0)
        assertThat(users.getValue(10).destinationOpenCount).isEqualTo(2)
        assertThat(users.getValue(10).viewableOpenRate).isEqualTo(1.0)
        assertThat(users.getValue(10).openRate).isEqualTo(2.0 / 3.0)
        assertThat(users.getValue(10).distinctDeviceCount).isEqualTo(2)
        assertThat(users.getValue(10).firstSelectedAt).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"))
        assertThat(users.getValue(10).lastSelectedAt).isEqualTo(Instant.parse("2026-08-03T00:00:00Z"))
        assertThat(users.getValue(10).lastViewedAt).isEqualTo(Instant.parse("2026-08-03T00:05:00Z"))
        assertThat(users.getValue(11).selectionCount).isEqualTo(1)
        assertThat(users.getValue(11).destinationOpenCount).isZero()
    }

    @Test
    fun `campaign users filter by identity query and aggregate open status`(): Unit = runBlocking {
        val opened = adapter.campaignUsers(7, query = "MEMBER@EXAMPLE.COM", status = "OPENED", limit = 20, offset = 0)
        val notOpened = adapter.campaignUsers(7, query = null, status = "NOT_OPENED", limit = 20, offset = 0)

        assertThat(opened.totalCount).isEqualTo(1)
        assertThat(opened.users).extracting<Long> { it.userId }.containsExactly(10)
        assertThat(notOpened.totalCount).isEqualTo(1)
        assertThat(notOpened.users).extracting<Long> { it.userId }.containsExactly(11)
    }

    @Test
    fun `ranking signals load campaign and user activity in batches`(): Unit = runBlocking {
        adapter.suppressCampaign(
            campaignId = 7,
            userId = 12,
            at = Instant.parse("2026-08-04T00:00:00Z"),
        )

        val performance = adapter.findCampaignPerformance(
            campaignIds = listOf(1, 7),
            since = Instant.parse("2026-08-01T15:00:00Z"),
        )
        val userSignals = adapter.findUserRankingSignals(
            campaignIds = listOf(1, 7),
            userId = 10,
            today = Instant.parse("2026-08-02T15:00:00Z"),
        )

        assertThat(performance.getValue(7).selections).isEqualTo(3)
        assertThat(performance.getValue(7).opens).isEqualTo(1)
        assertThat(performance.getValue(7).suppressions).isEqualTo(1)
        assertThat(performance.getValue(1).selections).isZero()
        assertThat(userSignals.getValue(7).selectionsToday).isEqualTo(1)
        assertThat(userSignals.getValue(7).latestSelectionAt)
            .isEqualTo(Instant.parse("2026-08-03T00:00:00Z"))
        assertThat(userSignals.getValue(7).latestOpenAt)
            .isEqualTo(Instant.parse("2026-08-03T00:05:00Z"))
        assertThat(userSignals).doesNotContainKey(1)
    }

    @Test
    fun `campaign suppression is user scoped and idempotent`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-10T00:00:00Z")

        adapter.suppressCampaign(campaignId = 7, userId = 10, at = now)
        adapter.suppressCampaign(campaignId = 7, userId = 10, at = now.plusSeconds(1))
        adapter.suppressCampaign(campaignId = 1, userId = 10, at = now)
        adapter.suppressCampaign(campaignId = 7, userId = 11, at = now)

        assertThat(adapter.findSuppressedCampaignIds(10)).containsExactlyInAnyOrder(1L, 7L)
        assertThat(adapter.findSuppressedCampaignIds(11)).containsExactly(7L)
        assertThat(adapter.findSuppressedCampaignIds(12)).isEmpty()
    }

    private suspend fun execute(sql: String) {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
    }

    private fun filter(
        query: String? = null,
        status: AdminNativeAdvertisementCampaignStatus? = null,
        audience: NativeAdvertisementAudience? = null,
    ) = AdminNativeAdvertisementCampaignFilter(
        query = query,
        status = status,
        audience = audience,
        evaluatedAt = Instant.parse("2026-08-10T00:00:00Z"),
    )
}
