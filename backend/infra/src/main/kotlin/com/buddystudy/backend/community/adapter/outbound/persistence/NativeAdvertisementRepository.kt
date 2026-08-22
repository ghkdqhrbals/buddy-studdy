package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserPage
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserSummary
import com.buddystudy.backend.community.application.port.outbound.AdminNativeAdvertisementPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementPort
import com.buddystudy.community.domain.entity.NativeAdvertisementCampaignEntity
import com.buddystudy.community.domain.entity.NativeAdvertisementSelectionEntity
import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

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
          and selected_at >= :since
          and viewed_at is not null
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
    private val database: DatabaseClient,
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
    val opens = long("destination_open_count").coerceIn(0, selections)
    return AdminNativeAdvertisementUserSummary(
        userId = long("user_id"),
        accountStatus = string("account_status"),
        email = get("email", String::class.java),
        displayName = get("display_name", String::class.java),
        selectionCount = selections,
        destinationOpenCount = opens,
        openRate = if (selections > 0) opens.toDouble() / selections else 0.0,
        distinctDeviceCount = long("distinct_device_count"),
        firstSelectedAt = instant("first_selected_at"),
        lastSelectedAt = instant("last_selected_at"),
        lastViewedAt = nullableInstant("last_viewed_at"),
    )
}

private fun Row.string(name: String): String = get(name, String::class.java).orEmpty()
private fun Row.long(name: String): Long = get(name, java.lang.Long::class.java)?.toLong() ?: 0L
private fun Row.instant(name: String): Instant = nullableInstant(name) ?: Instant.EPOCH
private fun Row.nullableInstant(name: String): Instant? =
    get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
