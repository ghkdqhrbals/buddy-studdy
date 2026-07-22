package com.buddystudy.backend.community.application.port.outbound

import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import com.buddystudy.community.domain.entity.QuestionSearchEntity
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
    suspend fun findByIdAndQuestionIdAndDeletedAtIsNull(id: Long, questionId: Long): QuestionCommentEntity?
    suspend fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(questionId: Long, pageable: Pageable): Page<QuestionCommentEntity>
}

interface ReportPort {
    suspend fun save(entity: ReportEntity): ReportEntity
}

interface QuestionSearchPort {
    suspend fun save(entity: QuestionSearchEntity): QuestionSearchEntity
    suspend fun deleteByQuestionId(questionId: Long): Long
    suspend fun deleteByStudyId(studyId: Long, userId: Long): Long
    suspend fun deleteByUserIdAndTopic(userId: Long, topic: String): Long
    suspend fun searchPublic(query: String?, language: String, limit: Int, offset: Int): SearchResult
    suspend fun findByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity?
    suspend fun findPublicByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity?
}

data class SearchResult(
    val questionIds: List<Long>,
    val totalCount: Long,
)
