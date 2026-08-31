package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.study.domain.QuestionLanguage
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
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

        val recordType = databaseClient.sql("select record_type from questions where id = :questionId and deleted_at is null")
            .bind("questionId", questionId).map { row, _ -> row.get("record_type", String::class.java)!! }
            .one().awaitSingleOrNull() ?: return
        if (recordType == "VOICE_TUTOR") {
            refreshVoice(questionId)
            return
        }

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

    /** Same canonical ID, but voice keeps its existing hash-fenced translation payload and source text. */
    private suspend fun refreshVoice(questionId: Long) {
        val source = databaseClient.sql(
            """
            select q.topic, q.question, q.answer, q.feedback, q.updated_at, r.source_hash,
                   r.depth_summary, r.strengths_json, r.improvements_json, r.source_languages_json
            from questions q
            join voice_study_learning_records r on r.id = q.voice_record_id and r.user_id = q.user_id
            join voice_tutor_sessions s on s.id = r.session_id and s.user_id = r.user_id
            join users u on u.id = q.user_id and u.status = 'ACTIVE'
            where q.id = :id and q.record_type = 'VOICE_TUTOR' and q.status = 'completed' and q.deleted_at is null
            """.trimIndent(),
        ).bind("id", questionId).map { row, _ ->
            val fields = linkedMapOf<String, String?>(
                "question" to row.get("question", String::class.java),
                "answer" to row.get("answer", String::class.java),
                "feedback" to row.get("feedback", String::class.java),
                "depthSummary" to row.get("depth_summary", String::class.java),
            ).apply {
                JsonMapperProvider.mapper.readTree(row.get("strengths_json", String::class.java)!!)
                    .forEachIndexed { index, value -> put("strength$index", value.asText()) }
                JsonMapperProvider.mapper.readTree(row.get("improvements_json", String::class.java)!!)
                    .forEachIndexed { index, value -> put("improvement$index", value.asText()) }
            }.filterValues { !it.isNullOrBlank() }
            VoiceSearchSource(
                row.get("topic", String::class.java)!!, row.get("source_hash", String::class.java)!!,
                fields, textMap(row.get("source_languages_json", String::class.java)),
                row.get("updated_at", java.time.LocalDateTime::class.java)!!,
            )
        }.one().awaitSingleOrNull() ?: return
        val translations = databaseClient.sql(
            """
            select l.target_language, l.fields_json from voice_study_learning_localizations l
            join questions q on q.voice_record_id = l.record_id
            where q.id = :id and q.record_type = 'VOICE_TUTOR' and q.deleted_at is null
              and l.status = 'READY' and l.source_hash = :hash
            """.trimIndent(),
        ).bind("id", questionId).bind("hash", source.hash).map { row, _ ->
            row.get("target_language", String::class.java)!! to textMap(row.get("fields_json", String::class.java))
        }.all().collectList().awaitSingle().toMap()
        for (language in QuestionLanguage.supported.sorted()) {
            val translated = translations[language]?.takeIf { fields ->
                source.fields.keys.all { !fields[it].isNullOrBlank() }
            }
            val fields = if (source.fields.keys.all { source.languages[it] == language }) source.fields else translated ?: source.fields
            val explanation = fields.filterKeys { it == "depthSummary" || it.startsWith("strength") || it.startsWith("improvement") }
                .values.filterNotNull().joinToString("\n").takeIf { it.isNotBlank() }
            databaseClient.sql(
                """
                insert into question_search(question_id, language, topic, question, answer, feedback, explanation, updated_at)
                select q.id, :language, :topic, :question, :answer, :feedback, :explanation, :updatedAt
                from questions q where q.id = :id and q.record_type = 'VOICE_TUTOR' and q.deleted_at is null
                """.trimIndent(),
            ).bind("id", questionId).bind("language", language).bind("topic", source.topic)
                .nullableText("question", fields["question"]).nullableText("answer", fields["answer"])
                .nullableText("feedback", fields["feedback"]).nullableText("explanation", explanation)
                .bind("updatedAt", source.updatedAt).fetch().rowsUpdated().awaitSingle()
        }
    }

    private fun textMap(json: String?): Map<String, String?> = json?.let {
        JsonMapperProvider.mapper.readTree(it).fields().asSequence()
            .associate { (key, value) -> key to if (value.isNull) null else value.asText() }
    }.orEmpty()

    private fun DatabaseClient.GenericExecuteSpec.nullableText(name: String, value: String?) =
        if (value == null) bindNull(name, String::class.java) else bind(name, value)

    private data class VoiceSearchSource(
        val topic: String, val hash: String, val fields: Map<String, String?>,
        val languages: Map<String, String?>, val updatedAt: java.time.LocalDateTime,
    )
}
