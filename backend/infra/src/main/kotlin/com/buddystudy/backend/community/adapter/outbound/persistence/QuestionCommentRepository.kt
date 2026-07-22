package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component

interface QuestionCommentRepository : CoroutineCrudRepository<QuestionCommentEntity, Long> {
    suspend fun findByIdAndQuestionIdAndDeletedAtIsNull(id: Long, questionId: Long): QuestionCommentEntity?
    fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(questionId: Long, pageable: Pageable): Flow<QuestionCommentEntity>
    suspend fun countByQuestionIdAndDeletedAtIsNull(questionId: Long): Long
}

@Component
class QuestionCommentPersistenceAdapter(
    private val repository: QuestionCommentRepository,
) : QuestionCommentPort {
    override suspend fun save(entity: QuestionCommentEntity) = repository.save(entity)
    override suspend fun findByIdAndQuestionIdAndDeletedAtIsNull(id: Long, questionId: Long) =
        repository.findByIdAndQuestionIdAndDeletedAtIsNull(id, questionId)
    override suspend fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(
        questionId: Long,
        pageable: Pageable,
    ): Page<QuestionCommentEntity> = PageImpl(
        repository.findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(questionId, pageable).toList(),
        pageable,
        repository.countByQuestionIdAndDeletedAtIsNull(questionId),
    )
}
