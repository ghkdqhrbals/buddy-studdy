package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.model.PublicQuestionSharePreview
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionSharePort
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

@Repository
class PublicQuestionSharePersistenceAdapter(private val database: DatabaseClient) : PublicQuestionSharePort {
    override suspend fun findPreview(questionId: Long, language: String): PublicQuestionSharePreview? = database.sql(
        """
        select q.id,
               coalesce(nullif(trim(qs.topic), ''), q.topic) as topic,
               coalesce(nullif(trim(qs.question), ''), q.question) as question,
               case when nullif(trim(qs.question), '') is not null then :language
                    else q.source_language end as content_language
        from questions q
        join users u on u.id = q.user_id
        -- Voice search rows may contain source fallback in a requested-language row. Keep the
        -- minimal voice preview in its canonical question language without translation side effects.
        left join question_search qs on qs.question_id = q.id and qs.language = :language
            and q.record_type = 'QUESTION'
        where q.id = :questionId
          and q.is_public = true and q.deleted_at is null
          and ${QuestionRepository.PUBLIC_ANSWER_CONDITION}
          and u.allow_public_questions = true
        """.trimIndent(),
    ).bind("questionId", questionId).bind("language", language)
        .map { row, _ ->
            PublicQuestionSharePreview(
                id = row.get("id", java.lang.Long::class.java)!!.toLong(),
                topic = row.get("topic", String::class.java).orEmpty(),
                question = row.get("question", String::class.java).orEmpty(),
                contentLanguage = row.get("content_language", String::class.java).orEmpty(),
            )
        }.one().awaitFirstOrNull()
}
