package com.buddystudy.backend.study.adapter.outbound.persistence

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class QuestionSearchProjectionManager(
    private val databaseClient: DatabaseClient,
) {
    @Transactional
    suspend fun refresh(questionId: Long) {
        databaseClient.sql("delete from question_search where question_id = :questionId")
            .bind("questionId", questionId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

        databaseClient.sql(
            """
            insert into question_search (
                question_id, language, topic, question, answer, feedback, explanation, updated_at
            )
            select
                q.id,
                languages.language,
                case
                    when q.source_language = languages.language then q.topic
                    when ql.status = 'READY' then ql.topic
                    else null
                end,
                case
                    when q.source_language = languages.language then q.question
                    when ql.status = 'READY' then ql.question
                    else null
                end,
                case
                    when q.answer_source_language = languages.language then q.answer
                    when al.status = 'READY' then al.answer
                    else null
                end,
                case
                    when q.ai_response_source_language = languages.language then q.feedback
                    when gl.status = 'READY' then gl.feedback
                    else null
                end,
                case
                    when q.ai_response_source_language = languages.language then q.explanation
                    when gl.status = 'READY' then gl.explanation
                    else null
                end,
                q.updated_at
            from questions q
            cross join (
                select 'ko' as language
                union all select 'en'
                union all select 'ja'
            ) languages
            left join question_localizations ql
              on ql.question_id = q.id and ql.target_language = languages.language
            left join answer_localizations al
              on al.question_id = q.id and al.target_language = languages.language
            left join grading_localizations gl
              on gl.question_id = q.id and gl.target_language = languages.language
            where q.id = :questionId
              and (
                q.source_language = languages.language
                or q.answer_source_language = languages.language
                or q.ai_response_source_language = languages.language
                or ql.status = 'READY'
                or al.status = 'READY'
                or gl.status = 'READY'
              )
            """.trimIndent(),
        )
            .bind("questionId", questionId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }
}
