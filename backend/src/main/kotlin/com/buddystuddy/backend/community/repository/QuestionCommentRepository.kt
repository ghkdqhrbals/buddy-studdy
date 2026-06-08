package com.buddystuddy.backend.community.repository

import com.buddystuddy.backend.domain.QuestionCommentEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface QuestionCommentRepository : JpaRepository<QuestionCommentEntity, Long> {
    fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtDesc(questionId: Long, pageable: Pageable): Page<QuestionCommentEntity>
}
