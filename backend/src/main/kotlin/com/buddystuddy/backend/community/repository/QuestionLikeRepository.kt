package com.buddystuddy.backend.community.repository

import com.buddystuddy.backend.domain.QuestionLikeEntity
import org.springframework.data.jpa.repository.JpaRepository

interface QuestionLikeRepository : JpaRepository<QuestionLikeEntity, Long> {
    fun findByQuestionIdAndUserId(questionId: Long, userId: Long): QuestionLikeEntity?
    fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean
    fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long
}
