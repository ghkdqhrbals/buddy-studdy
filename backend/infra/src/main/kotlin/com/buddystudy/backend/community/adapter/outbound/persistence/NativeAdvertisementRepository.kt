package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementPort
import com.buddystudy.backend.community.application.port.outbound.AdminNativeAdvertisementPort
import com.buddystudy.community.domain.entity.NativeAdvertisementCampaignEntity
import com.buddystudy.community.domain.entity.NativeAdvertisementSelectionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component
import java.time.Instant

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

    @Query(
        """
        select *
        from native_ad_campaigns
        order by created_at desc, id desc
        limit :limit offset :offset
        """
    )
    fun findAdminPage(limit: Int, offset: Int): Flow<NativeAdvertisementCampaignEntity>

    suspend fun findByCampaignKey(campaignKey: String): NativeAdvertisementCampaignEntity?
}

interface NativeAdvertisementSelectionRepository : CoroutineCrudRepository<NativeAdvertisementSelectionEntity, Long> {
    suspend fun findBySelectionId(selectionId: String): NativeAdvertisementSelectionEntity?

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
        select max(selected_at)
        from native_ad_selection_history
        where campaign_id = :campaignId and user_id = :userId
        """
    )
    suspend fun latestUserSelectionAt(campaignId: Long, userId: Long): Instant?

    @Query(
        """
        select max(viewed_at)
        from native_ad_selection_history
        where campaign_id = :campaignId and user_id = :userId
        """
    )
    suspend fun latestUserViewAt(campaignId: Long, userId: Long): Instant?

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
          and viewed_at is not null
          and viewed_at >= :since
        """
    )
    suspend fun countCampaignViewsSince(campaignId: Long, since: Instant): Long

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

@Component
class NativeAdvertisementPersistenceAdapter(
    private val campaigns: NativeAdvertisementCampaignRepository,
    private val selections: NativeAdvertisementSelectionRepository,
) : NativeAdvertisementPort, AdminNativeAdvertisementPort {
    override suspend fun findEligibleCampaigns(placement: String, now: Instant) =
        campaigns.findEligible(placement, now).toList()

    override suspend fun countUserSelectionsSince(campaignId: Long, userId: Long, since: Instant) =
        selections.countUserSelectionsSince(campaignId, userId, since)

    override suspend fun latestUserSelectionAt(campaignId: Long, userId: Long) =
        selections.latestUserSelectionAt(campaignId, userId)

    override suspend fun latestUserViewAt(campaignId: Long, userId: Long) =
        selections.latestUserViewAt(campaignId, userId)

    override suspend fun countCampaignSelectionsSince(campaignId: Long, since: Instant) =
        selections.countCampaignSelectionsSince(campaignId, since)

    override suspend fun countCampaignViewsSince(campaignId: Long, since: Instant) =
        selections.countCampaignViewsSince(campaignId, since)

    override suspend fun saveSelection(entity: NativeAdvertisementSelectionEntity) = selections.save(entity)

    override suspend fun findSelection(selectionId: String) = selections.findBySelectionId(selectionId)

    override suspend fun markView(selectionId: String, userId: Long, deviceId: String, at: Instant) {
        selections.markView(selectionId, userId, deviceId, at)
    }

    override suspend fun countCampaigns(): Long = campaigns.count()

    override suspend fun findCampaigns(limit: Int, offset: Int) = campaigns.findAdminPage(limit, offset).toList()

    override suspend fun findCampaign(id: Long) = campaigns.findById(id)

    override suspend fun findCampaignByKey(campaignKey: String) = campaigns.findByCampaignKey(campaignKey)

    override suspend fun saveCampaign(entity: NativeAdvertisementCampaignEntity) = campaigns.save(entity)

    override suspend fun countSelectionsSince(campaignId: Long, since: Instant) =
        selections.countCampaignSelectionsSince(campaignId, since)

    override suspend fun countViewsSince(campaignId: Long, since: Instant) =
        selections.countCampaignViewsSince(campaignId, since)
}
