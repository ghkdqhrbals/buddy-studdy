package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface QuestionCommentRepository : JpaRepository<QuestionCommentEntity, Long>, QuestionCommentPort {
    override fun findByIdAndQuestionIdAndDeletedAtIsNull(id: Long, questionId: Long): QuestionCommentEntity?
    override fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(questionId: Long, pageable: Pageable): Page<QuestionCommentEntity>
}
