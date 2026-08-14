package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component

interface QuestionCommentRepository : CoroutineCrudRepository<QuestionCommentEntity, Long> {
    suspend fun findByIdAndQuestionIdAndDeletedAtIsNull(id: Long, questionId: Long): QuestionCommentEntity?
    fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(questionId: Long, pageable: Pageable): Flow<QuestionCommentEntity>
    suspend fun countByQuestionIdAndDeletedAtIsNull(questionId: Long): Long

    @Query(
        """
        select qc.*
        from question_comments qc
        where qc.question_id = :questionId
          and qc.deleted_at is null
          and not exists (
              select 1
              from user_blocks user_block
              where user_block.blocker_user_id = :viewerUserId
                and user_block.blocked_user_id = qc.user_id
          )
        order by qc.created_at asc, qc.id asc
        limit :limit offset :offset
        """,
    )
    fun findVisibleByQuestionIdOrderByCreatedAtAsc(
        questionId: Long,
        viewerUserId: Long,
        limit: Int,
        offset: Long,
    ): Flow<QuestionCommentEntity>

    @Query(
        """
        select count(*)
        from question_comments qc
        where qc.question_id = :questionId
          and qc.deleted_at is null
          and not exists (
              select 1
              from user_blocks user_block
              where user_block.blocker_user_id = :viewerUserId
                and user_block.blocked_user_id = qc.user_id
          )
        """,
    )
    suspend fun countVisibleByQuestionId(questionId: Long, viewerUserId: Long): Long
}

@Component
class QuestionCommentPersistenceAdapter(
    private val repository: QuestionCommentRepository,
) : QuestionCommentPort {
    override suspend fun save(entity: QuestionCommentEntity) = repository.save(entity)
    override suspend fun findById(id: Long) = repository.findById(id)
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

    override suspend fun findVisibleByQuestionIdOrderByCreatedAtAsc(
        questionId: Long,
        viewerUserId: Long?,
        pageable: Pageable,
    ): Page<QuestionCommentEntity> {
        if (viewerUserId == null) {
            return findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(questionId, pageable)
        }
        return PageImpl(
            repository.findVisibleByQuestionIdOrderByCreatedAtAsc(
                questionId = questionId,
                viewerUserId = viewerUserId,
                limit = pageable.pageSize,
                offset = pageable.offset,
            ).toList(),
            pageable,
            repository.countVisibleByQuestionId(questionId, viewerUserId),
        )
    }
}
