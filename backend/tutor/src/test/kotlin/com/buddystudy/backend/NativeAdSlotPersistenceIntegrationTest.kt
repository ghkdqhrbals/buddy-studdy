package com.buddystudy.backend

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystudy.backend.community.application.port.outbound.NativeAdSlotPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdEligibilityPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdSlotReservation
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementPort
import com.buddystudy.community.domain.entity.NativeAdvertisementSelectionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.Instant

@SpringBootTest
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ]
)
class NativeAdSlotPersistenceIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var database: DatabaseClient
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var slots: NativeAdSlotPort
    @Autowired lateinit var eligibility: NativeAdEligibilityPort
    @Autowired lateinit var advertisements: NativeAdvertisementPort

    private lateinit var user: UserEntity

    @BeforeEach
    fun setUp() = runBlocking {
        database.sql("delete from native_ad_selection_history").fetch().rowsUpdated().awaitSingle()
        database.sql("delete from native_ad_slots").fetch().rowsUpdated().awaitSingle()
        database.sql("delete from native_ad_delivery_state").fetch().rowsUpdated().awaitSingle()
        user = users.save(
            UserEntity(
                provider = UserProvider.EMAIL,
                providerId = "native-slot-${System.nanoTime()}@example.com",
                email = "native-slot-${System.nanoTime()}@example.com",
                status = UserStatus.ACTIVE,
                displayName = "Native-Slot-${System.nanoTime()}",
            )
        )
    }

    @Test
    fun `concurrent delivery reservation is atomic across devices and resets its cap on the next UTC day`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-25T01:00:00Z")
        val concurrent = coroutineScope {
            (1..8).map { index ->
                async(Dispatchers.IO) {
                    slots.reserveSlot(
                        reservation("slot-concurrent-$index", now, "native-slot-device-$index"),
                        2,
                        60,
                    )
                }
            }.awaitAll()
        }

        assertThat(concurrent.filterNotNull()).hasSize(1)
        assertThat(slots.reserveSlot(reservation("slot-too-soon", now.plusSeconds(59)), 2, 60)).isNull()
        assertThat(slots.reserveSlot(reservation("slot-second", now.plusSeconds(60)), 2, 60)).isNotNull()
        assertThat(slots.reserveSlot(reservation("slot-over-cap", now.plusSeconds(120)), 2, 60)).isNull()
        assertThat(slots.reserveSlot(reservation("slot-next-day", now.plusSeconds(86_400)), 2, 60)).isNotNull()

        val persisted = count("select count(*) from native_ad_slots where user_id = ${user.id}")
        assertThat(persisted).isEqualTo(3)
    }

    @Test
    fun `ad eligibility follows the authoritative effective tier and fails closed when unresolved`(): Unit = runBlocking {
        val anonymous = users.save(
            UserEntity(
                provider = UserProvider.ANONYMOUS,
                providerId = "native-slot-anonymous-${System.nanoTime()}",
                status = UserStatus.ANONYMOUS,
                displayName = "Buddy",
            )
        )
        assertThat(eligibility.isAdFree(anonymous.id)).isFalse()
        assertThat(eligibility.isAdFree(user.id)).isNull()
        database.sql(
            """
            insert into user_entitlement_projection (
                user_id, subscription_id, tier_code, source, access_status, renewal_status,
                product_id, started_at, expires_at, will_renew, pending_product_id, projected_at, version
            ) values (
                :userId, null, 'TIER1', 'FREE', 'ACTIVE', 'NOT_APPLICABLE',
                null, null, null, false, null, utc_timestamp(6), 0
            )
            """.trimIndent(),
        ).bind("userId", user.id).fetch().rowsUpdated().awaitSingle()
        assertThat(eligibility.isAdFree(user.id)).isFalse()

        database.sql(
            "update user_entitlement_projection " +
                "set tier_code = 'TIER2', source = 'APP_STORE' where user_id = :userId",
        )
            .bind("userId", user.id).fetch().rowsUpdated().awaitSingle()
        assertThat(eligibility.isAdFree(user.id)).isTrue()

        database.sql("update user_entitlement_projection set access_status = 'UNKNOWN' where user_id = :userId")
            .bind("userId", user.id).fetch().rowsUpdated().awaitSingle()
        assertThat(eligibility.isAdFree(user.id)).isNull()
    }

    @Test
    fun `fallback selection and AdMob evidence are idempotent and cascade with account deletion`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-25T02:00:00Z")
        val slot = requireNotNull(slots.reserveSlot(reservation("slot-evidence", now), 2, 60))
        val campaign = advertisements.findEligibleCampaigns("COMMUNITY_FEED", now).first()
        val concurrentFallbacks = coroutineScope {
            (1..8).map { index ->
                async(Dispatchers.IO) {
                    advertisements.saveFallbackSelectionIfAbsent(
                        slot.slotId,
                        selection("selection-$index", slot.slotId, campaign.id, now.plusSeconds(index.toLong())),
                    )
                }
            }.awaitAll()
        }

        slots.markAdMobImpression(slot.slotId, user.id, "native-slot-device", now.plusSeconds(2))
        slots.markAdMobImpression(slot.slotId, user.id, "native-slot-device", now.plusSeconds(3))
        slots.markAdMobClick(slot.slotId, user.id, "native-slot-device", now.plusSeconds(4))
        slots.markAdMobClick(slot.slotId, user.id, "native-slot-device", now.plusSeconds(5))

        assertThat(concurrentFallbacks.map { it.selectionId }.distinct()).hasSize(1)
        assertThat(count("select count(*) from native_ad_selection_history where native_ad_slot_id = 'slot-evidence'"))
            .isEqualTo(1)
        val metrics = advertisements.findSelectionByNativeAdSlotId(slot.slotId)
        assertThat(metrics?.selectionId).isEqualTo(concurrentFallbacks.first().selectionId)

        users.deleteById(user.id)
        assertThat(count("select count(*) from native_ad_slots where user_id = ${user.id}")).isZero()
        assertThat(count("select count(*) from native_ad_delivery_state where user_id = ${user.id}")).isZero()
        assertThat(count("select count(*) from native_ad_selection_history where user_id = ${user.id}")).isZero()
    }

    private fun reservation(
        slotId: String,
        deliveredAt: Instant,
        deviceId: String = "native-slot-device",
    ) = NativeAdSlotReservation(
        slotId = slotId,
        userId = user.id,
        deviceId = deviceId,
        placement = "COMMUNITY_FEED",
        language = "ko",
        position = 2,
        feedItemCount = 4,
        deliveredAt = deliveredAt,
    )

    private fun selection(selectionId: String, slotId: String, campaignId: Long, selectedAt: Instant) =
        NativeAdvertisementSelectionEntity(
            selectionId = selectionId,
            campaignId = campaignId,
            userId = user.id,
            deviceId = "native-slot-device",
            placement = "COMMUNITY_FEED",
            language = "ko",
            position = 2,
            rankScore = BigDecimal.ONE,
            selectedAt = selectedAt,
            nativeAdSlotId = slotId,
        )

    private suspend fun count(sql: String): Long = database.sql(sql)
        .map { row, _ -> row.get(0, java.lang.Long::class.java)!!.toLong() }
        .one().awaitSingle()
}
