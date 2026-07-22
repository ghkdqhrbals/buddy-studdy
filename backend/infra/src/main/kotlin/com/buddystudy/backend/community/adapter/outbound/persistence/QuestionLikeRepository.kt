package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.QuestionLikePort
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component

interface QuestionLikeRepository : CoroutineCrudRepository<QuestionLikeEntity, Long> {
    suspend fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean

    @Query("select question_id from question_likes where user_id = :userId and question_id in (:questionIds)")
    suspend fun findLikedQuestionIdsInternal(@Param("userId") userId: Long, @Param("questionIds") questionIds: Collection<Long>): List<Long>

    suspend fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long
}

@Component
class QuestionLikePersistenceAdapter(
    private val repository: QuestionLikeRepository,
) : QuestionLikePort {
    override suspend fun save(entity: QuestionLikeEntity) = repository.save(entity)
    override suspend fun existsByQuestionIdAndUserId(questionId: Long, userId: Long) =
        repository.existsByQuestionIdAndUserId(questionId, userId)
    override suspend fun findLikedQuestionIds(userId: Long, questionIds: Collection<Long>): Set<Long> =
        if (questionIds.isEmpty()) emptySet() else repository.findLikedQuestionIdsInternal(userId, questionIds).toSet()
    override suspend fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long) =
        repository.deleteByQuestionIdAndUserId(questionId, userId)
}
