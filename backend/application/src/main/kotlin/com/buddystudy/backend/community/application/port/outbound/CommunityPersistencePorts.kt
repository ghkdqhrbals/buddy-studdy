package com.buddystudy.backend.community.application.port.outbound

import com.buddystudy.community.domain.entity.FeedbackEntity
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import com.buddystudy.community.domain.entity.ReportEntity
import com.buddystudy.community.domain.entity.UserBlockEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

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
