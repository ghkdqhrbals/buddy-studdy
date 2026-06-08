package com.buddystuddy.backend.community.adapter.outbound.persistence

import com.buddystuddy.backend.community.application.port.outbound.QuestionLikePort
import com.buddystuddy.community.domain.entity.QuestionLikeEntity
import org.springframework.data.jpa.repository.JpaRepository

interface QuestionLikeRepository : JpaRepository<QuestionLikeEntity, Long>, QuestionLikePort {
    fun findByQuestionIdAndUserId(questionId: Long, userId: Long): QuestionLikeEntity?
    override fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean
    override fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long
}
