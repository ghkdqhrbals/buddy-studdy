package com.buddystuddy.backend.community.application.port.outbound

import com.buddystuddy.domain.QuestionCommentEntity
import com.buddystuddy.domain.QuestionLikeEntity
import com.buddystuddy.domain.ReportEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface QuestionLikePort {
    fun save(entity: QuestionLikeEntity): QuestionLikeEntity
    fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean
    fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long
}

interface QuestionCommentPort {
    fun save(entity: QuestionCommentEntity): QuestionCommentEntity
    fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtDesc(questionId: Long, pageable: Pageable): Page<QuestionCommentEntity>
}

interface ReportPort {
    fun save(entity: ReportEntity): ReportEntity
}
