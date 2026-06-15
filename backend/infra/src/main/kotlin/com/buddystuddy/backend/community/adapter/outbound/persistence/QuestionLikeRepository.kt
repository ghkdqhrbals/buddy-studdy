package com.buddystuddy.backend.community.adapter.outbound.persistence

import com.buddystuddy.backend.community.application.port.outbound.QuestionLikePort
import com.buddystuddy.community.domain.entity.QuestionLikeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface QuestionLikeRepository : JpaRepository<QuestionLikeEntity, Long>, QuestionLikePort {
    fun findByQuestionIdAndUserId(questionId: Long, userId: Long): QuestionLikeEntity?
    override fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean
    @Query("select l.questionId from QuestionLikeEntity l where l.userId = :userId and l.questionId in :questionIds")
    override fun findLikedQuestionIds(@Param("userId") userId: Long, @Param("questionIds") questionIds: Collection<Long>): Set<Long>
    override fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long
    override fun countByQuestionId(questionId: Long): Long
}
