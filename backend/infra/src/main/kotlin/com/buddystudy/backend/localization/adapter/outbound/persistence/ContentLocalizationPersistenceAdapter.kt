package com.buddystudy.backend.localization.adapter.outbound.persistence

import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.backend.localization.application.model.RecordSourceHashes
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ContentLocalizationPersistenceAdapter(
    private val databaseClient: DatabaseClient,
) : ContentLocalizationPort {
    override suspend fun record(questionId: Long, targetLanguage: String) = RecordLocalizationSnapshot(
        question = snapshot(
            "question_localizations",
            "question_id",
            questionId,
            targetLanguage,
            listOf("topic", "question", "hint"),
        ),
        answer = snapshot(
            "answer_localizations",
            "question_id",
            questionId,
            targetLanguage,
            listOf("answer"),
        ),
        aiResponse = snapshot(
            "grading_localizations",
            "question_id",
            questionId,
            targetLanguage,
            listOf("feedback", "explanation", "assessment_json"),
        ),
    )

    override suspend fun comment(commentId: Long, targetLanguage: String) = snapshot(
        "question_comment_localizations",
        "comment_id",
        commentId,
        targetLanguage,
        listOf("body"),
    )

    override suspend fun ensureRecordPending(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHashes: RecordSourceHashes,
        now: Instant,
    ): Boolean {
        var changed = false
        if (QuestionLanguage.normalize(question.sourceLanguage) != targetLanguage) {
            changed = upsertPending(
                table = "question_localizations",
                idColumn = "question_id",
                id = question.id,
                targetLanguage = targetLanguage,
                sourceLanguage = question.sourceLanguage,
                sourceHash = sourceHashes.question,
                values = mapOf(
                    "topic" to question.topic,
                    "question" to question.question,
                    "hint" to question.hint,
                ),
                now = now,
            ) || changed
        }
        question.answer?.takeIf(String::isNotBlank)?.let { answer ->
            val source = question.answerSourceLanguage ?: question.sourceLanguage
            if (QuestionLanguage.normalize(source) != targetLanguage) {
                changed = upsertPending(
                    "answer_localizations",
                    "question_id",
                    question.id,
                    targetLanguage,
                    source,
                    sourceHashes.answer ?: return@let,
                    mapOf("answer" to answer),
                    now,
                ) || changed
            }
        }
        if (!question.feedback.isNullOrBlank() || !question.explanation.isNullOrBlank()) {
            val source = question.aiResponseSourceLanguage ?: question.sourceLanguage
            val aiResponseHash = sourceHashes.aiResponse
            if (QuestionLanguage.normalize(source) != targetLanguage && aiResponseHash != null) {
                changed = upsertPending(
                    "grading_localizations",
                    "question_id",
                    question.id,
                    targetLanguage,
                    source,
                    aiResponseHash,
                    mapOf(
                        "feedback" to question.feedback,
                        "explanation" to question.explanation,
                        "assessment_json" to question.gradingAssessmentJson,
                    ),
                    now,
                ) || changed
            }
        }
        return changed
    }

    override suspend fun ensureCommentPending(
        comment: QuestionCommentEntity,
        targetLanguage: String,
        sourceHash: String,
        now: Instant,
    ): Boolean {
        if (QuestionLanguage.normalize(comment.sourceLanguage) == targetLanguage) return false
        return upsertPending(
            "question_comment_localizations",
            "comment_id",
            comment.id,
            targetLanguage,
            comment.sourceLanguage,
            sourceHash,
            mapOf("body" to comment.body),
            now,
        )
    }

    override suspend fun saveRecordReady(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHashes: RecordSourceHashes,
        result: ContentTranslationResult,
        now: Instant,
    ): Boolean {
        var changed = false
        if (result.fields.containsKey("question")) {
            changed = updateReady(
                "question_localizations",
                "question_id",
                question.id,
                targetLanguage,
                sourceHashes.question,
                mapOf(
                    "topic" to result.fields["topic"],
                    "question" to result.fields["question"],
                    "hint" to result.fields["hint"],
                ),
                result.provider,
                now,
            ) || changed
        }
        if (result.fields.containsKey("answer")) {
            changed = updateReady(
                "answer_localizations",
                "question_id",
                question.id,
                targetLanguage,
                sourceHashes.answer ?: return changed,
                mapOf("answer" to result.fields["answer"]),
                result.provider,
                now,
            ) || changed
        }
        if (result.fields.containsKey("feedback") || result.fields.containsKey("explanation")) {
            changed = updateReady(
                "grading_localizations",
                "question_id",
                question.id,
                targetLanguage,
                sourceHashes.aiResponse ?: return changed,
                mapOf(
                    "feedback" to result.fields["feedback"],
                    "explanation" to result.fields["explanation"],
                    "assessment_json" to result.fields["assessmentJson"],
                ),
                result.provider,
                now,
            ) || changed
        }
        return changed
    }

    override suspend fun saveCommentReady(
        comment: QuestionCommentEntity,
        targetLanguage: String,
        sourceHash: String,
        result: ContentTranslationResult,
        now: Instant,
    ): Boolean = updateReady(
        "question_comment_localizations",
        "comment_id",
        comment.id,
        targetLanguage,
        sourceHash,
        mapOf("body" to result.fields["body"]),
        result.provider,
        now,
    )

    override suspend fun markFailed(
        event: ContentTranslationRequestedEvent,
        error: String,
        now: Instant,
    ) {
        val tables = when (event.contentType) {
            LocalizableContentType.RECORD -> listOfNotNull(
                event.questionSourceHash?.let { Triple("question_localizations", "question_id", it) },
                event.answerSourceHash?.let { Triple("answer_localizations", "question_id", it) },
                event.aiResponseSourceHash?.let { Triple("grading_localizations", "question_id", it) },
            )
            LocalizableContentType.COMMENT ->
                listOf(Triple("question_comment_localizations", "comment_id", event.sourceHash))
        }
        tables.forEach { (table, idColumn, sourceHash) ->
            databaseClient.sql(
                """
                update $table
                set status = 'FAILED', error = :error, updated_at = :now
                where $idColumn = :id and target_language = :targetLanguage
                  and source_hash = :sourceHash
                """.trimIndent(),
            )
                .bind("error", error.take(1000))
                .bind("now", now)
                .bind("id", event.contentId)
                .bind("targetLanguage", event.targetLanguage)
                .bind("sourceHash", sourceHash)
                .fetch().rowsUpdated().awaitSingle()
        }
    }

    private suspend fun snapshot(
        table: String,
        idColumn: String,
        id: Long,
        targetLanguage: String,
        fields: List<String>,
    ): TextLocalizationSnapshot? {
        val columns = fields.joinToString(", ")
        return databaseClient.sql(
            """
            select source_language, target_language, source_hash, status, provider, $columns
            from $table
            where $idColumn = :id and target_language = :targetLanguage
            """.trimIndent(),
        )
            .bind("id", id)
            .bind("targetLanguage", targetLanguage)
            .map { row, _ ->
                TextLocalizationSnapshot(
                    sourceLanguage = row.get("source_language", String::class.java)!!,
                    targetLanguage = row.get("target_language", String::class.java)!!,
                    sourceHash = row.get("source_hash", String::class.java)!!,
                    status = row.get("status", String::class.java)!!,
                    fields = fields.associate { field ->
                        field.camelKey() to row.get(field, String::class.java)
                    },
                    provider = row.get("provider", String::class.java),
                )
            }
            .one()
            .awaitSingleOrNull()
    }

    private suspend fun upsertPending(
        table: String,
        idColumn: String,
        id: Long,
        targetLanguage: String,
        sourceLanguage: String,
        sourceHash: String,
        values: Map<String, String?>,
        now: Instant,
    ): Boolean {
        val columns = values.keys.joinToString(", ")
        val markers = values.keys.joinToString(", ") { ":$it" }
        val refreshAssignments = values.keys.joinToString(",\n") { column ->
            "$column = if(source_hash <> values(source_hash), values($column), $column)"
        }
        var spec = databaseClient.sql(
            """
            insert into $table (
                $idColumn, target_language, source_language, source_hash,
                $columns, status, translation_version, created_at, updated_at
            ) values (
                :id, :targetLanguage, :sourceLanguage, :sourceHash,
                $markers, 'PENDING', 1, :now, :now
            )
            on duplicate key update
                $refreshAssignments,
                status = if(source_hash <> values(source_hash), 'PENDING', status),
                error = if(source_hash <> values(source_hash), null, error),
                updated_at = if(source_hash <> values(source_hash), values(updated_at), updated_at),
                source_language = values(source_language),
                source_hash = values(source_hash)
            """.trimIndent(),
        )
            .bind("id", id)
            .bind("targetLanguage", targetLanguage)
            .bind("sourceLanguage", QuestionLanguage.normalize(sourceLanguage))
            .bind("sourceHash", sourceHash)
            .bind("now", now)
        values.forEach { (name, value) ->
            spec = if (value == null) spec.bindNull(name, String::class.java) else spec.bind(name, value)
        }
        return spec.fetch().rowsUpdated().awaitSingle() > 0
    }

    private suspend fun updateReady(
        table: String,
        idColumn: String,
        id: Long,
        targetLanguage: String,
        sourceHash: String,
        values: Map<String, String?>,
        provider: String,
        now: Instant,
    ): Boolean {
        val assignments = values.keys.joinToString(", ") { "$it = :$it" }
        var spec = databaseClient.sql(
            """
            update $table
            set $assignments, status = 'READY', provider = :provider,
                error = null, updated_at = :now
            where $idColumn = :id and target_language = :targetLanguage
              and source_hash = :sourceHash
            """.trimIndent(),
        )
            .bind("provider", provider)
            .bind("now", now)
            .bind("id", id)
            .bind("targetLanguage", targetLanguage)
            .bind("sourceHash", sourceHash)
        values.forEach { (name, value) ->
            spec = if (value == null) spec.bindNull(name, String::class.java) else spec.bind(name, value)
        }
        return spec.fetch().rowsUpdated().awaitSingle() > 0
    }

    private fun String.camelKey(): String =
        split('_').let { parts ->
            parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
        }
}
