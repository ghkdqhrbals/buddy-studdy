package com.buddystuddy.backend.community.application.port.outbound

import com.buddystuddy.community.domain.entity.QuestionCommentEntity
import com.buddystuddy.community.domain.entity.QuestionLikeEntity
import com.buddystuddy.community.domain.entity.QuestionSearchEntity
import com.buddystuddy.community.domain.entity.ReportEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface QuestionLikePort {
    fun save(entity: QuestionLikeEntity): QuestionLikeEntity
    fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean
    fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long
    fun countByQuestionId(questionId: Long): Long
}

interface QuestionCommentPort {
    fun save(entity: QuestionCommentEntity): QuestionCommentEntity
    fun findByIdAndQuestionIdAndDeletedAtIsNull(id: Long, questionId: Long): QuestionCommentEntity?
    fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(questionId: Long, pageable: Pageable): Page<QuestionCommentEntity>
}

interface ReportPort {
    fun save(entity: ReportEntity): ReportEntity
}

interface QuestionSearchPort {
    fun save(entity: QuestionSearchEntity): QuestionSearchEntity
    fun deleteByQuestionId(questionId: Long): Long
    fun searchPublic(query: String?, limit: Int, offset: Int): SearchResult
}

interface QuestionSearchPublishPort {
    fun publishCreated(questionId: Long): Boolean
}

data class SearchResult(
    val questionIds: List<Long>,
    val totalCount: Long,
)
