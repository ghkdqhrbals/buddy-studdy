package com.buddystudy.backend.community.application.port.outbound

import com.buddystudy.community.domain.entity.FeedbackEntity
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import com.buddystudy.community.domain.entity.ReportEntity
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
}

interface ReportPort {
    suspend fun save(entity: ReportEntity): ReportEntity
}

interface FeedbackPort {
    suspend fun save(entity: FeedbackEntity): FeedbackEntity
}
