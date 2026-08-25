package com.buddystudy.backend.community.application.port.outbound

import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementCampaignFilter
import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserPage
import com.buddystudy.backend.community.application.model.AdminNativeAdPlacementMetrics
import com.buddystudy.community.domain.entity.FeedbackEntity
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import com.buddystudy.community.domain.entity.ReportEntity
import com.buddystudy.community.domain.entity.NativeAdvertisementCampaignEntity
import com.buddystudy.community.domain.entity.NativeAdvertisementSelectionEntity
import com.buddystudy.community.domain.entity.NativeAdPlacementPolicyEntity
import com.buddystudy.community.domain.entity.NativeAdSlotEntity
import com.buddystudy.community.domain.entity.UserBlockEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant

data class NativeAdvertisementUserRankingSignals(
    val campaignId: Long,
    val selectionsToday: Long,
    val latestSelectionAt: Instant?,
    val latestOpenAt: Instant?,
)

data class NativeAdvertisementCampaignPerformance(
    val campaignId: Long,
    val selections: Long,
    val opens: Long,
    val suppressions: Long,
    val impressions: Long = 0,
)

interface QuestionLikePort {
    suspend fun save(entity: QuestionLikeEntity): QuestionLikeEntity
    suspend fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean
    suspend fun findLikedQuestionIds(userId: Long, questionIds: Collection<Long>): Set<Long>
    suspend fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long
}

interface QuestionCommentPort {
    suspend fun save(entity: QuestionCommentEntity): QuestionCommentEntity
    suspend fun findById(id: Long): QuestionCommentEntity? = null
    suspend fun findByIdAndQuestionIdAndDeletedAtIsNull(id: Long, questionId: Long): QuestionCommentEntity?
    suspend fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(questionId: Long, pageable: Pageable): Page<QuestionCommentEntity>
    suspend fun findVisibleByQuestionIdOrderByCreatedAtAsc(
        questionId: Long,
        viewerUserId: Long?,
        pageable: Pageable,
    ): Page<QuestionCommentEntity> = if (viewerUserId == null) {
        findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(questionId, pageable)
    } else {
        error("The comment persistence adapter must implement blocked-author visibility filtering.")
    }
}

interface ReportPort {
    suspend fun save(entity: ReportEntity): ReportEntity
}

interface UserBlockPort {
    suspend fun insertIfAbsent(entity: UserBlockEntity): Boolean
    suspend fun exists(blockerUserId: Long, blockedUserId: Long): Boolean
    suspend fun findBlockedUserIds(blockerUserId: Long): Set<Long>
    suspend fun delete(blockerUserId: Long, blockedUserId: Long): Long
}

interface FeedbackPort {
    suspend fun save(entity: FeedbackEntity): FeedbackEntity
}

interface NativeAdvertisementPort {
    suspend fun findEligibleCampaigns(placement: String, now: Instant): List<NativeAdvertisementCampaignEntity>
    suspend fun findCampaign(id: Long): NativeAdvertisementCampaignEntity?
    suspend fun findUserRankingSignals(
        campaignIds: Collection<Long>,
        userId: Long,
        today: Instant,
    ): Map<Long, NativeAdvertisementUserRankingSignals>
    suspend fun findCampaignPerformance(
        campaignIds: Collection<Long>,
        since: Instant,
    ): Map<Long, NativeAdvertisementCampaignPerformance>
    suspend fun saveSelection(entity: NativeAdvertisementSelectionEntity): NativeAdvertisementSelectionEntity
    suspend fun saveFallbackSelectionIfAbsent(
        slotId: String,
        entity: NativeAdvertisementSelectionEntity,
    ): NativeAdvertisementSelectionEntity
    suspend fun findSelectionByNativeAdSlotId(slotId: String): NativeAdvertisementSelectionEntity?
    suspend fun findSelection(selectionId: String): NativeAdvertisementSelectionEntity?
    suspend fun markImpression(selectionId: String, userId: Long, deviceId: String, at: Instant)
    suspend fun markView(selectionId: String, userId: Long, deviceId: String, at: Instant)
    suspend fun findSuppressedCampaignIds(userId: Long): Set<Long>
    suspend fun suppressCampaign(campaignId: Long, userId: Long, at: Instant)
}

interface NativeAdEligibilityPort {
    /** Returns null when the authoritative entitlement cannot be resolved. */
    suspend fun isAdFree(userId: Long): Boolean?
}

data class NativeAdSlotReservation(
    val slotId: String,
    val userId: Long,
    val deviceId: String,
    val placement: String,
    val language: String,
    val position: Int,
    val feedItemCount: Int,
    val deliveredAt: Instant,
)

interface NativeAdSlotPort {
    suspend fun findPlacementPolicy(placement: String): NativeAdPlacementPolicyEntity?
    suspend fun savePlacementPolicy(entity: NativeAdPlacementPolicyEntity): NativeAdPlacementPolicyEntity
    suspend fun reserveSlot(
        reservation: NativeAdSlotReservation,
        dailyDeliveryCap: Int,
        minimumSecondsBetweenDeliveries: Int,
    ): NativeAdSlotEntity?
    suspend fun findOwnedSlot(slotId: String, userId: Long, deviceId: String): NativeAdSlotEntity?
    suspend fun markAdMobImpression(slotId: String, userId: Long, deviceId: String, at: Instant)
    suspend fun markAdMobClick(slotId: String, userId: Long, deviceId: String, at: Instant)
}

interface AdminNativeAdvertisementPort {
    suspend fun countCampaigns(filter: AdminNativeAdvertisementCampaignFilter): Long
    suspend fun findCampaigns(
        filter: AdminNativeAdvertisementCampaignFilter,
        limit: Int,
        offset: Int,
    ): List<NativeAdvertisementCampaignEntity>
    suspend fun findCampaign(id: Long): NativeAdvertisementCampaignEntity?
    suspend fun findCampaignByKey(campaignKey: String): NativeAdvertisementCampaignEntity?
    suspend fun saveCampaign(entity: NativeAdvertisementCampaignEntity): NativeAdvertisementCampaignEntity
    suspend fun findCampaignPerformance(
        campaignIds: Collection<Long>,
        since: Instant,
    ): Map<Long, NativeAdvertisementCampaignPerformance>
    suspend fun campaignUsers(
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementUserPage
    suspend fun findPlacementPolicy(placement: String): NativeAdPlacementPolicyEntity?
    suspend fun savePlacementPolicy(entity: NativeAdPlacementPolicyEntity): NativeAdPlacementPolicyEntity
    suspend fun placementMetrics(placement: String, since: Instant): AdminNativeAdPlacementMetrics
}
