package com.buddystudy.backend.community.application.port.outbound

import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import com.buddystudy.community.domain.entity.QuestionSearchEntity
import com.buddystudy.community.domain.entity.ReportEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface QuestionLikePort {
    fun save(entity: QuestionLikeEntity): QuestionLikeEntity
    fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean
    fun findLikedQuestionIds(userId: Long, questionIds: Collection<Long>): Set<Long>
    fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long
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
    fun deleteByStudyId(studyId: Long, userId: Long): Long
    fun searchPublic(query: String?, language: String, limit: Int, offset: Int): SearchResult
    fun findByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity?
    fun findPublicByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity?
}

data class SearchResult(
    val questionIds: List<Long>,
    val totalCount: Long,
)
