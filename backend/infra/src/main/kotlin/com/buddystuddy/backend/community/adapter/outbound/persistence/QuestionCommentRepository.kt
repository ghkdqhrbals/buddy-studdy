package com.buddystuddy.backend.community.adapter.outbound.persistence

import com.buddystuddy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystuddy.community.domain.entity.QuestionCommentEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface QuestionCommentRepository : JpaRepository<QuestionCommentEntity, Long>, QuestionCommentPort {
    override fun findByIdAndQuestionIdAndDeletedAtIsNull(id: Long, questionId: Long): QuestionCommentEntity?
    override fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtDesc(questionId: Long, pageable: Pageable): Page<QuestionCommentEntity>
}
