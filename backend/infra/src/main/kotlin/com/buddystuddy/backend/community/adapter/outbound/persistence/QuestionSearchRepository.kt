package com.buddystuddy.backend.community.adapter.outbound.persistence

import com.buddystuddy.backend.community.application.port.outbound.QuestionSearchPort
import com.buddystuddy.backend.community.application.port.outbound.SearchResult
import com.buddystuddy.community.domain.entity.QuestionSearchEntity
import com.buddystuddy.community.domain.entity.QuestionSearchId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface QuestionSearchJpaRepository : JpaRepository<QuestionSearchEntity, QuestionSearchId> {
    @Modifying
    @Query("delete from QuestionSearchEntity q where q.questionId = :questionId")
    fun deleteSearchRow(@Param("questionId") questionId: Long): Long

    @Query(
        value = """
            select qs.question_id
            from question_search qs
            join users u on u.id = qs.user_id
            where qs.public_question = true
              and qs.score is not null
              and qs.deleted_at is null
              and u.allow_public_questions = true
            group by qs.question_id
            order by max(qs.created_at) desc
            limit :limit offset :offset
        """,
        nativeQuery = true,
    )
    fun listPublic(@Param("limit") limit: Int, @Param("offset") offset: Int): List<Number>

    @Query(
        value = """
            select count(distinct qs.question_id)
            from question_search qs
            join users u on u.id = qs.user_id
            where qs.public_question = true
              and qs.score is not null
              and qs.deleted_at is null
              and u.allow_public_questions = true
        """,
        nativeQuery = true,
    )
    fun countPublic(): Long

    @Query(
        value = """
            select qs.question_id
            from question_search qs
            join users u on u.id = qs.user_id
            where qs.public_question = true
              and qs.score is not null
              and qs.deleted_at is null
              and u.allow_public_questions = true
              and qs.search_vector @@ websearch_to_tsquery('simple', :query)
            group by qs.question_id
            order by max(ts_rank_cd(qs.search_vector, websearch_to_tsquery('simple', :query))) desc, max(qs.created_at) desc
            limit :limit offset :offset
        """,
        nativeQuery = true,
    )
    fun searchPublic(@Param("query") query: String, @Param("limit") limit: Int, @Param("offset") offset: Int): List<Number>

    @Query(
        value = """
            select count(distinct qs.question_id)
            from question_search qs
            join users u on u.id = qs.user_id
            where qs.public_question = true
              and qs.score is not null
              and qs.deleted_at is null
              and u.allow_public_questions = true
              and qs.search_vector @@ websearch_to_tsquery('simple', :query)
        """,
        nativeQuery = true,
    )
    fun countSearchPublic(@Param("query") query: String): Long
}

@Repository
class QuestionSearchRepository(
    private val jpa: QuestionSearchJpaRepository,
) : QuestionSearchPort {
    override fun save(entity: QuestionSearchEntity): QuestionSearchEntity = jpa.save(entity)

    @Transactional
    override fun deleteByQuestionId(questionId: Long): Long = jpa.deleteSearchRow(questionId)

    override fun searchPublic(query: String?, limit: Int, offset: Int): SearchResult {
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = maxOf(offset, 0)
        val ids = if (query.isNullOrBlank()) {
            jpa.listPublic(safeLimit, safeOffset)
        } else {
            jpa.searchPublic(query, safeLimit, safeOffset)
        }.map { it.toLong() }
        val total = if (query.isNullOrBlank()) jpa.countPublic() else jpa.countSearchPublic(query)
        return SearchResult(ids, total)
    }
}
