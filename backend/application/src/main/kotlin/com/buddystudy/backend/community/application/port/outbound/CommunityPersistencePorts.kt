package com.buddystudy.backend.community.application.port.outbound

import com.buddystudy.backend.community.application.model.AdminNativeAdvertisementUserPage
import com.buddystudy.community.domain.entity.FeedbackEntity
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import com.buddystudy.community.domain.entity.ReportEntity
import com.buddystudy.community.domain.entity.UserBlockEntity
import com.buddystudy.community.domain.entity.NativeAdvertisementCampaignEntity
import com.buddystudy.community.domain.entity.NativeAdvertisementSelectionEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant

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
    suspend fun countUserSelectionsSince(campaignId: Long, userId: Long, since: Instant): Long
    suspend fun latestUserSelectionAt(campaignId: Long, userId: Long): Instant?
    suspend fun latestUserViewAt(campaignId: Long, userId: Long): Instant?
    suspend fun countCampaignSelectionsSince(campaignId: Long, since: Instant): Long
    suspend fun countCampaignViewsSince(campaignId: Long, since: Instant): Long
    suspend fun saveSelection(entity: NativeAdvertisementSelectionEntity): NativeAdvertisementSelectionEntity
    suspend fun findSelection(selectionId: String): NativeAdvertisementSelectionEntity?
    suspend fun markView(selectionId: String, userId: Long, deviceId: String, at: Instant)
}

interface AdminNativeAdvertisementPort {
    suspend fun countCampaigns(): Long
    suspend fun findCampaigns(limit: Int, offset: Int): List<NativeAdvertisementCampaignEntity>
    suspend fun findCampaign(id: Long): NativeAdvertisementCampaignEntity?
    suspend fun findCampaignByKey(campaignKey: String): NativeAdvertisementCampaignEntity?
    suspend fun saveCampaign(entity: NativeAdvertisementCampaignEntity): NativeAdvertisementCampaignEntity
    suspend fun countSelectionsSince(campaignId: Long, since: Instant): Long
    suspend fun countViewsSince(campaignId: Long, since: Instant): Long
    suspend fun campaignUsers(
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminNativeAdvertisementUserPage
}
