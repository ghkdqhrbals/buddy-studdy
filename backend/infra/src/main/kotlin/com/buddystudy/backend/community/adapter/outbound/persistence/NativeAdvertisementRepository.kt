package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignFilter
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignStatus
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserPage
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserSummary
import com.buddystudy.backend.community.application.model.AdminNativeAdPlacementMetrics
import com.buddystudy.backend.community.application.port.outbound.AdminNativeAdvertisementPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementCampaignPerformance
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementUserRankingSignals
import com.buddystudy.backend.community.application.port.outbound.NativeAdEligibilityPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdSlotPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdSlotReservation
import com.buddystudy.community.domain.entity.NativeAdvertisementAudience
import com.buddystudy.community.domain.entity.NativeAdvertisementCampaignEntity
import com.buddystudy.community.domain.entity.NativeAdvertisementSelectionEntity
import com.buddystudy.community.domain.entity.NativeAdPlacementPolicyEntity
import com.buddystudy.community.domain.entity.NativeAdSlotEntity
import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

interface NativeAdvertisementCampaignRepository : CoroutineCrudRepository<NativeAdvertisementCampaignEntity, Long> {
    @Query(
        """
        select *
        from native_ad_campaigns
        where placement = :placement
          and active = true
          and (starts_at is null or starts_at <= :now)
          and (ends_at is null or ends_at > :now)
        order by base_priority desc, id asc
        """
    )
    fun findEligible(placement: String, now: Instant): Flow<NativeAdvertisementCampaignEntity>

    suspend fun findByCampaignKey(campaignKey: String): NativeAdvertisementCampaignEntity?
}

interface NativeAdvertisementSelectionRepository : CoroutineCrudRepository<NativeAdvertisementSelectionEntity, Long> {
    suspend fun findBySelectionId(selectionId: String): NativeAdvertisementSelectionEntity?
    suspend fun findByNativeAdSlotId(nativeAdSlotId: String): NativeAdvertisementSelectionEntity?

    @Query(
        """
        select count(*)
        from native_ad_selection_history
        where campaign_id = :campaignId
          and user_id = :userId
          and selected_at >= :since
        """
    )
    suspend fun countUserSelectionsSince(campaignId: Long, userId: Long, since: Instant): Long

    @Query(
        """
        select count(*)
        from native_ad_selection_history
        where campaign_id = :campaignId
          and selected_at >= :since
        """
    )
    suspend fun countCampaignSelectionsSince(campaignId: Long, since: Instant): Long

    @Query(
        """
        select count(*)
        from native_ad_selection_history
        where campaign_id = :campaignId
          and selected_at >= :since
          and viewed_at is not null
        """
    )
    suspend fun countCampaignViewsSince(campaignId: Long, since: Instant): Long

    @Modifying
    @Query(
        """
        update native_ad_selection_history
        set impression_at = coalesce(impression_at, :at)
        where selection_id = :selectionId
          and user_id = :userId
          and device_id = :deviceId
        """
    )
    suspend fun markImpression(selectionId: String, userId: Long, deviceId: String, at: Instant): Long

    @Modifying
    @Query(
        """
        update native_ad_selection_history
        set viewed_at = coalesce(viewed_at, :at)
        where selection_id = :selectionId
          and user_id = :userId
          and device_id = :deviceId
        """
    )
    suspend fun markView(selectionId: String, userId: Long, deviceId: String, at: Instant): Long
}

interface NativeAdPlacementPolicyRepository : CoroutineCrudRepository<NativeAdPlacementPolicyEntity, String>

interface NativeAdSlotRepository : CoroutineCrudRepository<NativeAdSlotEntity, Long> {
    suspend fun findBySlotIdAndUserIdAndDeviceId(slotId: String, userId: Long, deviceId: String): NativeAdSlotEntity?
}

@Component
class NativeAdvertisementPersistenceAdapter(
    private val campaigns: NativeAdvertisementCampaignRepository,
    private val selections: NativeAdvertisementSelectionRepository,
    private val placementPolicies: NativeAdPlacementPolicyRepository,
    private val nativeAdSlots: NativeAdSlotRepository,
    private val database: DatabaseClient,
) : NativeAdvertisementPort, NativeAdEligibilityPort, NativeAdSlotPort, AdminNativeAdvertisementPort {
    override suspend fun findEligibleCampaigns(placement: String, now: Instant) =
        campaigns.findEligible(placement, now).toList()

    override suspend fun findUserRankingSignals(
        campaignIds: Collection<Long>,
        userId: Long,
        today: Instant,
    ): Map<Long, NativeAdvertisementUserRankingSignals> {
        if (campaignIds.isEmpty()) return emptyMap()
        return database.sql(
            """
            select campaign_id,
                   sum(case when selected_at >= :today then 1 else 0 end) as selections_today,
                   max(selected_at) as latest_selection_at,
                   max(viewed_at) as latest_open_at
            from native_ad_selection_history
            where campaign_id in (:campaignIds)
              and user_id = :userId
            group by campaign_id
            """.trimIndent(),
        ).bind("campaignIds", campaignIds.toList())
            .bind("userId", userId)
            .bind("today", today)
            .map { row, _ ->
                NativeAdvertisementUserRankingSignals(
                    campaignId = row.long("campaign_id"),
                    selectionsToday = row.long("selections_today"),
                    latestSelectionAt = row.nullableInstant("latest_selection_at"),
                    latestOpenAt = row.nullableInstant("latest_open_at"),
                )
            }
            .all()
            .collectList()
            .awaitSingle()
            .associateBy { it.campaignId }
    }

    override suspend fun findCampaignPerformance(
        campaignIds: Collection<Long>,
        since: Instant,
    ): Map<Long, NativeAdvertisementCampaignPerformance> {
        if (campaignIds.isEmpty()) return emptyMap()
        val ids = campaignIds.distinct()
        val history = database.sql(
            """
            select campaign_id,
                   count(*) as selection_count,
                   sum(case when impression_at is not null then 1 else 0 end) as impression_count,
                   sum(case when viewed_at is not null then 1 else 0 end) as open_count
            from native_ad_selection_history
            where campaign_id in (:campaignIds)
              and selected_at >= :since
            group by campaign_id
            """.trimIndent(),
        ).bind("campaignIds", ids)
            .bind("since", since)
            .map { row, _ ->
                row.long("campaign_id") to CampaignHistoryCounts(
                    selections = row.long("selection_count"),
                    impressions = row.long("impression_count"),
                    opens = row.long("open_count"),
                )
            }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()
        val suppressions = database.sql(
            """
            select campaign_id, count(*) as suppression_count
            from native_ad_campaign_suppressions
            where campaign_id in (:campaignIds)
              and created_at >= :since
            group by campaign_id
            """.trimIndent(),
        ).bind("campaignIds", ids)
            .bind("since", since)
            .map { row, _ -> row.long("campaign_id") to row.long("suppression_count") }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()
        return ids.associateWith { campaignId ->
            val counts = history[campaignId] ?: CampaignHistoryCounts()
            NativeAdvertisementCampaignPerformance(
                campaignId = campaignId,
                selections = counts.selections,
                impressions = counts.impressions,
                opens = counts.opens,
                suppressions = suppressions[campaignId] ?: 0,
            )
        }
    }

    override suspend fun saveSelection(entity: NativeAdvertisementSelectionEntity) = selections.save(entity)

    @Transactional
    override suspend fun saveFallbackSelectionIfAbsent(
        slotId: String,
        entity: NativeAdvertisementSelectionEntity,
    ): NativeAdvertisementSelectionEntity {
        val ownedSlot = database.sql(
            """
            select slot_id
            from native_ad_slots
            where slot_id = :slotId
              and user_id = :userId
              and device_id = :deviceId
            for update
            """.trimIndent(),
        ).bind("slotId", slotId)
            .bind("userId", entity.userId)
            .bind("deviceId", entity.deviceId)
            .map { _, _ -> true }
            .one()
            .awaitSingleOrNull()
        check(ownedSlot == true) { "Native advertisement slot is not owned by the fallback selection principal." }
        findFallbackSelectionForUpdate(slotId)?.let { return it }

        database.sql(
            """
            insert ignore into native_ad_selection_history (
                selection_id, campaign_id, user_id, device_id, placement, language, position,
                rank_score, selected_at, impression_at, viewed_at, native_ad_slot_id
            ) values (
                :selectionId, :campaignId, :userId, :deviceId, :placement, :language, :position,
                :rankScore, :selectedAt, null, null, :slotId
            )
            """.trimIndent(),
        ).bind("selectionId", entity.selectionId)
            .bind("campaignId", entity.campaignId)
            .bind("userId", entity.userId)
            .bind("deviceId", entity.deviceId)
            .bind("placement", entity.placement)
            .bind("language", entity.language)
            .bind("position", entity.position)
            .bind("rankScore", entity.rankScore)
            .bind("selectedAt", entity.selectedAt)
            .bind("slotId", slotId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return findFallbackSelectionForUpdate(slotId)
            ?: error("Native advertisement fallback selection was not persisted.")
    }

    private suspend fun findFallbackSelectionForUpdate(slotId: String): NativeAdvertisementSelectionEntity? =
        database.sql(
            """
            select id, selection_id, campaign_id, user_id, device_id, placement, language, position,
                   rank_score, selected_at, impression_at, viewed_at, native_ad_slot_id
            from native_ad_selection_history
            where native_ad_slot_id = :slotId
            for update
            """.trimIndent(),
        ).bind("slotId", slotId)
            .map { row, _ -> row.toNativeAdvertisementSelection() }
            .one()
            .awaitSingleOrNull()

    override suspend fun findSelectionByNativeAdSlotId(slotId: String) = selections.findByNativeAdSlotId(slotId)

    override suspend fun findSelection(selectionId: String) = selections.findBySelectionId(selectionId)

    override suspend fun markImpression(selectionId: String, userId: Long, deviceId: String, at: Instant) {
        selections.markImpression(selectionId, userId, deviceId, at)
    }

    override suspend fun markView(selectionId: String, userId: Long, deviceId: String, at: Instant) {
        selections.markView(selectionId, userId, deviceId, at)
    }

    override suspend fun findSuppressedCampaignIds(userId: Long): Set<Long> =
        database.sql(
            """
            select campaign_id
            from native_ad_campaign_suppressions
            where user_id = :userId
            """.trimIndent(),
        ).bind("userId", userId)
            .map { row, _ -> row.long("campaign_id") }
            .all()
            .collectList()
            .awaitSingle()
            .toSet()

    override suspend fun suppressCampaign(campaignId: Long, userId: Long, at: Instant) {
        database.sql(
            """
            insert ignore into native_ad_campaign_suppressions (campaign_id, user_id, created_at)
            values (:campaignId, :userId, :createdAt)
            """.trimIndent(),
        ).bind("campaignId", campaignId)
            .bind("userId", userId)
            .bind("createdAt", at)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    override suspend fun isAdFree(userId: Long): Boolean? = database.sql(
        """
        select case when u.status = 'ANONYMOUS' then false else effective.ad_free end as ad_free
        from users u
        left join (
            select ranked.user_id, ranked.ad_free
            from (
                select
                    candidates.user_id,
                    candidates.ad_free,
                    row_number() over (
                        partition by candidates.user_id
                        order by candidates.tier_rank desc, candidates.monthly_question_limit desc,
                                 candidates.changed_at desc
                    ) as effective_rank
                from (
                    select
                        entitlement.user_id,
                        tier.ad_free,
                        tier.monthly_question_limit,
                        case entitlement.tier_code
                            when 'TIER3' then 3
                            when 'TIER2' then 2
                            when 'TIER1' then 1
                            else 0
                        end as tier_rank,
                        entitlement.projected_at as changed_at
                    from user_entitlement_projection entitlement
                    join user_membership_tiers tier on tier.tier_code = entitlement.tier_code
                    where entitlement.user_id = :userId
                      and (
                            entitlement.source = 'FREE'
                            or entitlement.access_status = 'GRACE_PERIOD'
                            or (
                                entitlement.access_status = 'ACTIVE'
                                and (entitlement.expires_at is null or entitlement.expires_at > current_timestamp)
                            )
                          )

                    union all

                    select
                        membership.user_id,
                        tier.ad_free,
                        coalesce(membership.monthly_question_limit_override, tier.monthly_question_limit),
                        case membership.tier
                            when 'TIER3' then 3
                            when 'TIER2' then 2
                            when 'TIER1' then 1
                            else 0
                        end as tier_rank,
                        membership.updated_at as changed_at
                    from user_memberships membership
                    join user_membership_tiers tier on tier.tier_code = membership.tier
                    where membership.user_id = :userId
                      and membership.status = 'ACTIVE'
                      and membership.started_at <= current_timestamp
                      and (membership.expires_at is null or membership.expires_at > current_timestamp)
                ) candidates
            ) ranked
            where ranked.effective_rank = 1
        ) effective on effective.user_id = u.id
        where u.id = :userId
          and (
              u.status = 'ANONYMOUS'
              or (
                  u.status = 'ACTIVE'
                  and effective.ad_free is not null
              )
          )
        """.trimIndent(),
    ).bind("userId", userId)
        .map { row, _ ->
            when (val value = row.get("ad_free")) {
                null -> true
                is Boolean -> value
                is Number -> value.toInt() != 0
                else -> throw IllegalStateException("Column ad_free is not boolean")
            }
        }
        .one()
        .awaitSingleOrNull()

    override suspend fun findPlacementPolicy(placement: String) = placementPolicies.findById(placement)

    override suspend fun savePlacementPolicy(entity: NativeAdPlacementPolicyEntity) = placementPolicies.save(entity)

    @Transactional
    override suspend fun reserveSlot(
        reservation: NativeAdSlotReservation,
        dailyDeliveryCap: Int,
        minimumSecondsBetweenDeliveries: Int,
    ): NativeAdSlotEntity? {
        if (dailyDeliveryCap <= 0) return null
        // Keep the lock order users -> delivery state -> slot so first-time state creation cannot deadlock.
        database.sql(
            """
            select id
            from users
            where id = :userId
            for update
            """.trimIndent(),
        ).bind("userId", reservation.userId)
            .map { row, _ -> row.long("id") }
            .one()
            .awaitSingleOrNull()
            ?: return null
        val deliveryDay = reservation.deliveredAt.atZone(ZoneOffset.UTC).toLocalDate()
        database.sql(
            """
            insert ignore into native_ad_delivery_state (
                user_id, placement, delivery_day, daily_count, last_delivered_at, updated_at
            ) values (:userId, :placement, :deliveryDay, 0, null, :updatedAt)
            """.trimIndent(),
        ).bind("userId", reservation.userId)
            .bind("placement", reservation.placement)
            .bind("deliveryDay", deliveryDay)
            .bind("updatedAt", reservation.deliveredAt)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        val state = database.sql(
            """
            select delivery_day, daily_count, last_delivered_at
            from native_ad_delivery_state
            where user_id = :userId and placement = :placement
            for update
            """.trimIndent(),
        ).bind("userId", reservation.userId)
            .bind("placement", reservation.placement)
            .map { row, _ ->
                NativeAdDeliveryState(
                    deliveryDay = row.get("delivery_day", LocalDate::class.java) ?: deliveryDay,
                    dailyCount = row.int("daily_count"),
                    lastDeliveredAt = row.nullableInstant("last_delivered_at"),
                )
            }
            .one()
            .awaitSingle()
        val countToday = if (state.deliveryDay == deliveryDay) state.dailyCount else 0
        if (countToday >= dailyDeliveryCap) return null
        if (
            minimumSecondsBetweenDeliveries > 0 &&
            state.lastDeliveredAt?.let {
                Duration.between(it, reservation.deliveredAt).seconds < minimumSecondsBetweenDeliveries
            } == true
        ) {
            return null
        }
        database.sql(
            """
            update native_ad_delivery_state
            set delivery_day = :deliveryDay,
                daily_count = :dailyCount,
                last_delivered_at = :deliveredAt,
                updated_at = :deliveredAt
            where user_id = :userId and placement = :placement
            """.trimIndent(),
        ).bind("deliveryDay", deliveryDay)
            .bind("dailyCount", countToday + 1)
            .bind("deliveredAt", reservation.deliveredAt)
            .bind("userId", reservation.userId)
            .bind("placement", reservation.placement)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return nativeAdSlots.save(
            NativeAdSlotEntity(
                slotId = reservation.slotId,
                userId = reservation.userId,
                deviceId = reservation.deviceId,
                placement = reservation.placement,
                language = reservation.language,
                position = reservation.position,
                feedItemCount = reservation.feedItemCount,
                deliveredAt = reservation.deliveredAt,
            )
        )
    }

    override suspend fun findOwnedSlot(slotId: String, userId: Long, deviceId: String) =
        nativeAdSlots.findBySlotIdAndUserIdAndDeviceId(slotId, userId, deviceId)

    override suspend fun markAdMobImpression(slotId: String, userId: Long, deviceId: String, at: Instant) {
        database.sql(
            """
            update native_ad_slots
            set ad_mob_impression_at = coalesce(ad_mob_impression_at, :at)
            where slot_id = :slotId and user_id = :userId and device_id = :deviceId
            """.trimIndent(),
        ).bind("at", at).bind("slotId", slotId).bind("userId", userId).bind("deviceId", deviceId)
            .fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun markAdMobClick(slotId: String, userId: Long, deviceId: String, at: Instant) {
        database.sql(
            """
            update native_ad_slots
            set ad_mob_click_at = coalesce(ad_mob_click_at, :at)
            where slot_id = :slotId and user_id = :userId and device_id = :deviceId
            """.trimIndent(),
        ).bind("at", at).bind("slotId", slotId).bind("userId", userId).bind("deviceId", deviceId)
            .fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun placementMetrics(placement: String, since: Instant): AdminNativeAdPlacementMetrics =
        database.sql(
            """
            select
                (select count(*) from native_ad_slots s where s.placement = :placement and s.delivered_at >= :since) slot_deliveries,
                (select count(*) from native_ad_slots s where s.placement = :placement and s.ad_mob_impression_at >= :since) admob_impressions,
                (select count(*) from native_ad_slots s where s.placement = :placement and s.ad_mob_click_at >= :since) admob_clicks,
                (select count(*) from native_ad_selection_history h where h.placement = :placement and h.selected_at >= :since and h.native_ad_slot_id is not null) fallback_selections,
                (select count(*) from native_ad_selection_history h where h.placement = :placement and h.impression_at >= :since and h.native_ad_slot_id is not null) fallback_impressions,
                (select count(*) from native_ad_selection_history h where h.placement = :placement and h.viewed_at >= :since and h.native_ad_slot_id is not null) fallback_opens
            """.trimIndent(),
        ).bind("placement", placement)
            .bind("since", since)
            .map { row, _ ->
                AdminNativeAdPlacementMetrics(
                    slotDeliveries = row.long("slot_deliveries"),
                    adMobImpressions = row.long("admob_impressions"),
                    adMobClicks = row.long("admob_clicks"),
                    fallbackSelections = row.long("fallback_selections"),
                    fallbackImpressions = row.long("fallback_impressions"),
                    fallbackOpens = row.long("fallback_opens"),
                )
            }.one().awaitSingle()

    override suspend fun countCampaigns(filter: AdminNativeAdvertisementCampaignFilter): Long {
        val where = campaignFilterWhere(filter)
        return database.sql("select count(*) as total_count from native_ad_campaigns c $where")
            .bindCampaignFilter(filter)
            .map { row, _ -> row.long("total_count") }
            .one()
            .awaitSingle()
    }

    override suspend fun findCampaigns(
        filter: AdminNativeAdvertisementCampaignFilter,
        limit: Int,
        offset: Int,
    ): List<NativeAdvertisementCampaignEntity> {
        val where = campaignFilterWhere(filter)
        return database.sql(
            """
            select c.*
            from native_ad_campaigns c
            $where
            order by c.created_at desc, c.id desc
            limit :limit offset :offset
            """.trimIndent(),
        ).bindCampaignFilter(filter)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row, _ -> row.toNativeAdvertisementCampaign() }
            .all()
            .collectList()
            .awaitSingle()
    }

    override suspend fun findCampaign(id: Long) = campaigns.findById(id)

    override suspend fun findCampaignByKey(campaignKey: String) = campaigns.findByCampaignKey(campaignKey)

    override suspend fun saveCampaign(entity: NativeAdvertisementCampaignEntity) = campaigns.save(entity)

    override suspend fun campaignUsers(
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementUserPage {
        val search = query?.lowercase()?.let { "%$it%" }
        val where = buildString {
            append("where h.campaign_id = :campaignId")
            if (search != null) {
                append(" and (lower(u.email) like :query or lower(u.display_name) like :query or cast(u.id as char) = :exactQuery)")
            }
        }
        val having = when (status) {
            "OPENED" -> "having sum(case when h.viewed_at is not null then 1 else 0 end) > 0"
            "NOT_OPENED" -> "having sum(case when h.viewed_at is not null then 1 else 0 end) = 0"
            else -> ""
        }
        val total = database.sql(
            """
            select count(*) as total_count
            from (
                select h.user_id
                from native_ad_selection_history h
                join users u on u.id = h.user_id
                $where
                group by h.user_id
                $having
            ) campaign_users
            """.trimIndent(),
        ).bind("campaignId", campaignId)
            .bindUserSearch(search, query)
            .map { row, _ -> row.long("total_count") }
            .one()
            .awaitSingle()
        val users = database.sql(
            """
            select h.user_id,
                   u.status as account_status,
                   u.email,
                   u.display_name,
                   count(*) as selection_count,
                   sum(case when h.impression_at is not null then 1 else 0 end) as impression_count,
                   sum(case when h.viewed_at is not null then 1 else 0 end) as destination_open_count,
                   count(distinct h.device_id) as distinct_device_count,
                   min(h.selected_at) as first_selected_at,
                   max(h.selected_at) as last_selected_at,
                   max(h.viewed_at) as last_viewed_at
            from native_ad_selection_history h
            join users u on u.id = h.user_id
            $where
            group by h.user_id, u.status, u.email, u.display_name
            $having
            order by last_selected_at desc, h.user_id desc
            limit :limit offset :offset
            """.trimIndent(),
        ).bind("campaignId", campaignId)
            .bindUserSearch(search, query)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row, _ -> row.toAdminNativeAdvertisementUser() }
            .all()
            .collectList()
            .awaitSingle()
        return AdminNativeAdvertisementUserPage(users, total, limit, offset)
    }
}

private data class NativeAdDeliveryState(
    val deliveryDay: LocalDate,
    val dailyCount: Int,
    val lastDeliveredAt: Instant?,
)

private data class CampaignHistoryCounts(
    val selections: Long = 0,
    val impressions: Long = 0,
    val opens: Long = 0,
)

private fun campaignFilterWhere(filter: AdminNativeAdvertisementCampaignFilter): String = buildString {
    append("where 1 = 1")
    if (filter.query != null) {
        append(
            " and (lower(c.campaign_key) like :campaignQuery" +
                " or lower(c.title_ko) like :campaignQuery" +
                " or lower(c.title_en) like :campaignQuery" +
                " or lower(c.title_ja) like :campaignQuery)",
        )
    }
    if (filter.audience != null) {
        append(" and c.audience = :campaignAudience")
    }
    when (filter.status) {
        AdminNativeAdvertisementCampaignStatus.PAUSED -> append(" and c.active = false")
        AdminNativeAdvertisementCampaignStatus.SCHEDULED ->
            append(" and c.active = true and c.starts_at is not null and c.starts_at > :evaluatedAt")
        AdminNativeAdvertisementCampaignStatus.ENDED -> append(
            " and c.active = true" +
                " and (c.starts_at is null or c.starts_at <= :evaluatedAt)" +
                " and c.ends_at is not null and c.ends_at <= :evaluatedAt",
        )
        AdminNativeAdvertisementCampaignStatus.ACTIVE -> append(
            " and c.active = true" +
                " and (c.starts_at is null or c.starts_at <= :evaluatedAt)" +
                " and (c.ends_at is null or c.ends_at > :evaluatedAt)",
        )
        null -> Unit
    }
}

private fun DatabaseClient.GenericExecuteSpec.bindCampaignFilter(
    filter: AdminNativeAdvertisementCampaignFilter,
): DatabaseClient.GenericExecuteSpec {
    var spec = this
    filter.query?.let { spec = spec.bind("campaignQuery", "%${it.lowercase(Locale.ROOT)}%") }
    filter.audience?.let { spec = spec.bind("campaignAudience", it.name) }
    if (filter.status in TIMED_CAMPAIGN_STATUSES) {
        spec = spec.bind("evaluatedAt", filter.evaluatedAt)
    }
    return spec
}

private val TIMED_CAMPAIGN_STATUSES = setOf(
    AdminNativeAdvertisementCampaignStatus.ACTIVE,
    AdminNativeAdvertisementCampaignStatus.SCHEDULED,
    AdminNativeAdvertisementCampaignStatus.ENDED,
)

private fun DatabaseClient.GenericExecuteSpec.bindUserSearch(
    search: String?,
    exactQuery: String?,
): DatabaseClient.GenericExecuteSpec = if (search == null) {
    this
} else {
    bind("query", search).bind("exactQuery", exactQuery.orEmpty())
}

private fun Row.toAdminNativeAdvertisementUser(): AdminNativeAdvertisementUserSummary {
    val selections = long("selection_count")
    val impressions = long("impression_count").coerceIn(0, selections)
    val opens = long("destination_open_count").coerceIn(0, selections)
    return AdminNativeAdvertisementUserSummary(
        userId = long("user_id"),
        accountStatus = string("account_status"),
        email = get("email", String::class.java),
        displayName = get("display_name", String::class.java),
        selectionCount = selections,
        impressionCount = impressions,
        impressionRate = if (selections > 0) impressions.toDouble() / selections else 0.0,
        destinationOpenCount = opens,
        openRate = if (selections > 0) opens.toDouble() / selections else 0.0,
        viewableOpenRate = if (impressions > 0) opens.coerceAtMost(impressions).toDouble() / impressions else 0.0,
        distinctDeviceCount = long("distinct_device_count"),
        firstSelectedAt = instant("first_selected_at"),
        lastSelectedAt = instant("last_selected_at"),
        lastViewedAt = nullableInstant("last_viewed_at"),
    )
}

private fun Row.toNativeAdvertisementCampaign() = NativeAdvertisementCampaignEntity(
    id = long("id"),
    campaignKey = string("campaign_key"),
    placement = string("placement"),
    audience = NativeAdvertisementAudience.valueOf(string("audience")),
    disclosureKo = string("disclosure_ko"),
    disclosureEn = string("disclosure_en"),
    disclosureJa = string("disclosure_ja"),
    titleKo = string("title_ko"),
    titleEn = string("title_en"),
    titleJa = string("title_ja"),
    bodyKo = get("body_ko", String::class.java),
    bodyEn = get("body_en", String::class.java),
    bodyJa = get("body_ja", String::class.java),
    imageUrl = get("image_url", String::class.java),
    affiliateDisclosureKo = get("affiliate_disclosure_ko", String::class.java),
    affiliateDisclosureEn = get("affiliate_disclosure_en", String::class.java),
    affiliateDisclosureJa = get("affiliate_disclosure_ja", String::class.java),
    deepLink = string("deep_link"),
    basePriority = decimal("base_priority"),
    authenticatedRelevance = decimal("authenticated_relevance"),
    anonymousRelevance = decimal("anonymous_relevance"),
    dailySelectionCap = int("daily_selection_cap"),
    minimumSecondsBetweenSelections = int("minimum_seconds_between_selections"),
    postViewCooldownSeconds = int("post_view_cooldown_seconds"),
    minimumFeedItemCount = int("minimum_feed_item_count"),
    earliestPosition = int("earliest_position"),
    latestPosition = int("latest_position"),
    active = get("active", java.lang.Boolean::class.java)?.booleanValue() ?: false,
    startsAt = nullableInstant("starts_at"),
    endsAt = nullableInstant("ends_at"),
    createdAt = instant("created_at"),
    updatedAt = instant("updated_at"),
)

private fun Row.toNativeAdvertisementSelection() = NativeAdvertisementSelectionEntity(
    id = long("id"),
    selectionId = string("selection_id"),
    campaignId = long("campaign_id"),
    userId = long("user_id"),
    deviceId = string("device_id"),
    placement = string("placement"),
    language = string("language"),
    position = int("position"),
    rankScore = decimal("rank_score"),
    selectedAt = instant("selected_at"),
    impressionAt = nullableInstant("impression_at"),
    viewedAt = nullableInstant("viewed_at"),
    nativeAdSlotId = get("native_ad_slot_id", String::class.java),
)

private fun Row.string(name: String): String = get(name, String::class.java).orEmpty()
private fun Row.long(name: String): Long = get(name, java.lang.Long::class.java)?.toLong() ?: 0L
private fun Row.int(name: String): Int = get(name, java.lang.Integer::class.java)?.toInt() ?: 0
private fun Row.decimal(name: String): BigDecimal = get(name, BigDecimal::class.java) ?: BigDecimal.ZERO
private fun Row.instant(name: String): Instant = nullableInstant(name) ?: Instant.EPOCH
private fun Row.nullableInstant(name: String): Instant? =
    get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
