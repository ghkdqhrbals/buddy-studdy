package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.QuestionSearchPort
import com.buddystudy.backend.community.application.port.outbound.SearchResult
import com.buddystudy.community.domain.entity.QuestionSearchEntity
import com.buddystudy.community.domain.entity.QuestionSearchId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import javax.sql.DataSource

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
              and qs.language = :language
              and u.allow_public_questions = true
            order by qs.created_at desc
            limit :limit offset :offset
        """,
        nativeQuery = true,
    )
    fun listPublic(
        @Param("language") language: String,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<Number>

    @Query(
        value = """
            select count(distinct qs.question_id)
            from question_search qs
            join users u on u.id = qs.user_id
            where qs.public_question = true
              and qs.score is not null
              and qs.deleted_at is null
              and qs.language = :language
              and u.allow_public_questions = true
        """,
        nativeQuery = true,
    )
    fun countPublic(@Param("language") language: String): Long

    @Query(
        value = """
            select qs.question_id
            from question_search qs
            join users u on u.id = qs.user_id
            where qs.public_question = true
              and qs.score is not null
              and qs.deleted_at is null
              and qs.language = :language
              and u.allow_public_questions = true
              and qs.search_vector @@ websearch_to_tsquery('simple', :query)
            order by ts_rank_cd(qs.search_vector, websearch_to_tsquery('simple', :query)) desc, qs.created_at desc
            limit :limit offset :offset
        """,
        nativeQuery = true,
    )
    fun searchPublic(
        @Param("query") query: String,
        @Param("language") language: String,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<Number>

    @Query(
        value = """
            select count(distinct qs.question_id)
            from question_search qs
            join users u on u.id = qs.user_id
            where qs.public_question = true
              and qs.score is not null
              and qs.deleted_at is null
              and qs.language = :language
              and u.allow_public_questions = true
              and qs.search_vector @@ websearch_to_tsquery('simple', :query)
        """,
        nativeQuery = true,
    )
    fun countSearchPublic(
        @Param("query") query: String,
        @Param("language") language: String,
    ): Long

    @Query(
        value = """
            select qs.question_id
            from question_search qs
            join users u on u.id = qs.user_id
            where qs.public_question = true
              and qs.score is not null
              and qs.deleted_at is null
              and qs.language = :language
              and u.allow_public_questions = true
              and (
                    lower(qs.topic) like concat('%', lower(:query), '%')
                 or lower(qs.question) like concat('%', lower(:query), '%')
                 or lower(coalesce(qs.answer, '')) like concat('%', lower(:query), '%')
                 or lower(coalesce(qs.feedback, '')) like concat('%', lower(:query), '%')
                 or lower(coalesce(qs.explanation, '')) like concat('%', lower(:query), '%')
              )
            order by qs.created_at desc
            limit :limit offset :offset
        """,
        nativeQuery = true,
    )
    fun searchPublicByLike(
        @Param("query") query: String,
        @Param("language") language: String,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<Number>

    @Query(
        value = """
            select count(distinct qs.question_id)
            from question_search qs
            join users u on u.id = qs.user_id
            where qs.public_question = true
              and qs.score is not null
              and qs.deleted_at is null
              and qs.language = :language
              and u.allow_public_questions = true
              and (
                    lower(qs.topic) like concat('%', lower(:query), '%')
                 or lower(qs.question) like concat('%', lower(:query), '%')
                 or lower(coalesce(qs.answer, '')) like concat('%', lower(:query), '%')
                 or lower(coalesce(qs.feedback, '')) like concat('%', lower(:query), '%')
                 or lower(coalesce(qs.explanation, '')) like concat('%', lower(:query), '%')
              )
        """,
        nativeQuery = true,
    )
    fun countSearchPublicByLike(
        @Param("query") query: String,
        @Param("language") language: String,
    ): Long

    fun findByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity?
}

@Repository
class QuestionSearchRepository(
    private val jpa: QuestionSearchJpaRepository,
    private val dataSource: DataSource,
) : QuestionSearchPort {
    private val postgresDatabase: Boolean by lazy { detectPostgres() }

    override fun save(entity: QuestionSearchEntity): QuestionSearchEntity = jpa.save(entity)

    @Transactional
    override fun deleteByQuestionId(questionId: Long): Long = jpa.deleteSearchRow(questionId)

    override fun searchPublic(query: String?, language: String, limit: Int, offset: Int): SearchResult {
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = maxOf(offset, 0)
        val normalizedLanguage = language.normalizedSearchLanguage()
        val ids = (if (query.isNullOrBlank()) {
            jpa.listPublic(normalizedLanguage, safeLimit, safeOffset)
        } else if (postgresDatabase) {
            jpa.searchPublic(query, normalizedLanguage, safeLimit, safeOffset)
        } else {
            jpa.searchPublicByLike(query, normalizedLanguage, safeLimit, safeOffset)
        }).map { it.toLong() }
        val total = if (query.isNullOrBlank()) {
            jpa.countPublic(normalizedLanguage)
        } else if (postgresDatabase) {
            jpa.countSearchPublic(query, normalizedLanguage)
        } else {
            jpa.countSearchPublicByLike(query, normalizedLanguage)
        }
        return SearchResult(ids, total)
    }

    override fun findByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? =
        jpa.findByQuestionIdAndLanguage(questionId, language.normalizedSearchLanguage())

    override fun findPublicByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? {
        val row = jpa.findByQuestionIdAndLanguage(questionId, language.normalizedSearchLanguage()) ?: return null
        return row.takeIf { it.publicQuestion && it.score != null && it.deletedAt == null }
    }

    private fun String.normalizedSearchLanguage(): String =
        if (lowercase().startsWith("en")) "en" else "ko"

    private fun detectPostgres(): Boolean =
        dataSource.connection.use { connection ->
            connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)
        }
}
