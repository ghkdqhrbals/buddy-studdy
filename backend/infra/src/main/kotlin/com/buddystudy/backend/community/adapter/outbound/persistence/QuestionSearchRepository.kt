package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.QuestionSearchPort
import com.buddystudy.backend.community.application.port.outbound.SearchResult
import com.buddystudy.community.domain.entity.QuestionSearchEntity
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.stereotype.Repository

@Repository
class QuestionSearchRepository(
    private val template: R2dbcEntityTemplate,
    connectionFactory: ConnectionFactory,
) : QuestionSearchPort {
    private val postgresDatabase = connectionFactory.metadata.name.contains("PostgreSQL", ignoreCase = true)

    override suspend fun save(entity: QuestionSearchEntity): QuestionSearchEntity {
        var spec = template.databaseClient.sql(
            """
            insert into question_search (
                question_id, language, user_id, topic, question, answer, feedback, explanation,
                author_display_name, public_question, score, answered_at, deleted_at, created_at, updated_at
            ) values (
                :questionId, :language, :userId, :topic, :question, :answer, :feedback, :explanation,
                :authorDisplayName, :publicQuestion, :score, :answeredAt, :deletedAt, :createdAt, :updatedAt
            )
            on conflict (question_id, language) do update set
                user_id = excluded.user_id, topic = excluded.topic, question = excluded.question,
                answer = excluded.answer, feedback = excluded.feedback, explanation = excluded.explanation,
                author_display_name = excluded.author_display_name, public_question = excluded.public_question,
                score = excluded.score, answered_at = excluded.answered_at, deleted_at = excluded.deleted_at,
                updated_at = excluded.updated_at
            """.trimIndent(),
        ).bind("questionId", entity.questionId)
            .bind("language", entity.language.normalizedSearchLanguage())
            .bind("userId", entity.userId)
            .bind("topic", entity.topic)
            .bind("question", entity.question)
            .bind("authorDisplayName", entity.authorDisplayName)
            .bind("publicQuestion", entity.publicQuestion)
            .bind("createdAt", entity.createdAt)
            .bind("updatedAt", entity.updatedAt)
        spec = spec.bindNullable("answer", entity.answer, String::class.java)
            .bindNullable("feedback", entity.feedback, String::class.java)
            .bindNullable("explanation", entity.explanation, String::class.java)
            .bindNullable("score", entity.score, Int::class.javaObjectType)
            .bindNullable("answeredAt", entity.answeredAt, java.time.Instant::class.java)
            .bindNullable("deletedAt", entity.deletedAt, java.time.Instant::class.java)
        spec.fetch().rowsUpdated().awaitSingle()
        return entity
    }

    override suspend fun deleteByQuestionId(questionId: Long): Long = rowsUpdated(
        "delete from question_search where question_id = :questionId",
        "questionId" to questionId,
    )

    override suspend fun deleteByStudyId(studyId: Long, userId: Long): Long = rowsUpdated(
        """
        delete from question_search qs where exists (
            select 1 from questions q where q.id = qs.question_id and q.study_id = :studyId and q.user_id = :userId
        )
        """.trimIndent(),
        "studyId" to studyId,
        "userId" to userId,
    )

    override suspend fun deleteByUserIdAndTopic(userId: Long, topic: String): Long = rowsUpdated(
        "delete from question_search where user_id = :userId and lower(topic) = lower(:topic)",
        "userId" to userId,
        "topic" to topic,
    )

    override suspend fun searchPublic(query: String?, language: String, limit: Int, offset: Int): SearchResult {
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        val normalizedLanguage = language.normalizedSearchLanguage()
        val predicate = when {
            query.isNullOrBlank() -> "true"
            postgresDatabase -> "qs.search_vector @@ websearch_to_tsquery('simple', :query)"
            else -> likePredicate()
        }
        val order = if (!query.isNullOrBlank() && postgresDatabase) {
            "ts_rank_cd(qs.search_vector, websearch_to_tsquery('simple', :query)) desc, qs.created_at desc"
        } else {
            "qs.created_at desc"
        }
        val base = publicBase(predicate)
        var idsSpec = template.databaseClient.sql("select qs.question_id $base order by $order limit :limit offset :offset")
            .bind("language", normalizedLanguage).bind("limit", safeLimit).bind("offset", safeOffset)
        var countSpec = template.databaseClient.sql("select count(distinct qs.question_id) as total $base")
            .bind("language", normalizedLanguage)
        if (!query.isNullOrBlank()) {
            idsSpec = idsSpec.bind("query", query)
            countSpec = countSpec.bind("query", query)
        }
        val ids = idsSpec.map { row, _ -> row.get("question_id", java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()
        val total = countSpec.map { row, _ -> row.get("total", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        return SearchResult(ids, total)
    }

    override suspend fun findByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? =
        template.selectOne(
            Query.query(
                Criteria.where("question_id").`is`(questionId)
                    .and("language").`is`(language.normalizedSearchLanguage()),
            ),
            QuestionSearchEntity::class.java,
        ).awaitSingleOrNull()

    override suspend fun findPublicByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? =
        findByQuestionIdAndLanguage(questionId, language)
            ?.takeIf { it.publicQuestion && it.score != null && it.deletedAt == null }

    private fun publicBase(predicate: String) =
        """
        from question_search qs join users u on u.id = qs.user_id
        where qs.public_question = true and qs.score is not null and qs.deleted_at is null
          and qs.language = :language and u.allow_public_questions = true and ($predicate)
        """.trimIndent()

    private fun likePredicate() =
        """
        lower(qs.topic) like concat('%', lower(:query), '%')
        or lower(qs.question) like concat('%', lower(:query), '%')
        or lower(coalesce(qs.answer, '')) like concat('%', lower(:query), '%')
        or lower(coalesce(qs.feedback, '')) like concat('%', lower(:query), '%')
        or lower(coalesce(qs.explanation, '')) like concat('%', lower(:query), '%')
        """.trimIndent()

    private suspend fun rowsUpdated(sql: String, vararg bindings: Pair<String, Any>): Long {
        var spec = template.databaseClient.sql(sql)
        bindings.forEach { (name, value) -> spec = spec.bind(name, value) }
        return spec.fetch().rowsUpdated().awaitSingle()
    }

    private fun String.normalizedSearchLanguage(): String = if (lowercase().startsWith("en")) "en" else "ko"

    private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: T?,
        type: Class<T>,
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)
}
