package com.buddystudy.backend.localization.adapter.outbound.persistence

import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.model.PendingContentTranslation
import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.backend.localization.application.model.RecordSourceHashes
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionSearchProjectionManager
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ContentLocalizationPersistenceAdapter(
    private val databaseClient: DatabaseClient,
    private val searchProjection: QuestionSearchProjectionManager,
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
        retryPendingBefore: Instant,
    ): List<PendingContentTranslation> {
        val pendingContent = mutableListOf<PendingContentTranslation>()
        if (QuestionLanguage.normalize(question.sourceLanguage) != targetLanguage) {
            upsertPending(
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
                retryPendingBefore = retryPendingBefore,
            )?.let { requestToken ->
                pendingContent += PendingContentTranslation(
                    LocalizableContentType.QUESTION,
                    sourceHashes.question,
                    requestToken,
                )
            }
        }
        question.answer?.takeIf(String::isNotBlank)?.let { answer ->
            val source = question.answerSourceLanguage ?: question.sourceLanguage
            if (QuestionLanguage.normalize(source) != targetLanguage) {
                val sourceHash = sourceHashes.answer ?: return@let
                upsertPending(
                    "answer_localizations",
                    "question_id",
                    question.id,
                    targetLanguage,
                    source,
                    sourceHash,
                    mapOf("answer" to answer),
                    now,
                    retryPendingBefore,
                )?.let { requestToken ->
                    pendingContent += PendingContentTranslation(
                        LocalizableContentType.ANSWER,
                        sourceHash,
                        requestToken,
                    )
                }
            }
        }
        if (!question.feedback.isNullOrBlank() || !question.explanation.isNullOrBlank()) {
            val source = question.aiResponseSourceLanguage ?: question.sourceLanguage
            val aiResponseHash = sourceHashes.aiResponse
            if (QuestionLanguage.normalize(source) != targetLanguage && aiResponseHash != null) {
                upsertPending(
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
                    retryPendingBefore,
                )?.let { requestToken ->
                    pendingContent += PendingContentTranslation(
                        LocalizableContentType.AI_RESPONSE,
                        aiResponseHash,
                        requestToken,
                    )
                }
            }
        }
        return pendingContent
    }

    override suspend fun ensureCommentPending(
        comment: QuestionCommentEntity,
        targetLanguage: String,
        sourceHash: String,
        now: Instant,
        retryPendingBefore: Instant,
    ): PendingContentTranslation? {
        if (QuestionLanguage.normalize(comment.sourceLanguage) == targetLanguage) return null
        return upsertPending(
            "question_comment_localizations",
            "comment_id",
            comment.id,
            targetLanguage,
            comment.sourceLanguage,
            sourceHash,
            mapOf("body" to comment.body),
            now,
            retryPendingBefore,
        )?.let { requestToken ->
            PendingContentTranslation(LocalizableContentType.COMMENT, sourceHash, requestToken)
        }
    }

    override suspend fun saveQuestionReady(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHash: String,
        result: ContentTranslationResult,
        now: Instant,
    ): Boolean = saveReadyAndRefreshQuestion(
        question.id,
        updateReady(
            "question_localizations",
            "question_id",
            question.id,
            targetLanguage,
            sourceHash,
            mapOf(
                "topic" to result.fields["topic"],
                "question" to result.fields["question"],
                "hint" to result.fields["hint"],
            ),
            result.provider,
            now,
        ),
    )

    override suspend fun saveAnswerReady(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHash: String,
        result: ContentTranslationResult,
        now: Instant,
    ): Boolean = saveReadyAndRefreshQuestion(
        question.id,
        updateReady(
            "answer_localizations",
            "question_id",
            question.id,
            targetLanguage,
            sourceHash,
            mapOf("answer" to result.fields["answer"]),
            result.provider,
            now,
        ),
    )

    override suspend fun saveAiResponseReady(
        question: QuestionEntity,
        targetLanguage: String,
        sourceHash: String,
        result: ContentTranslationResult,
        now: Instant,
    ): Boolean = saveReadyAndRefreshQuestion(
        question.id,
        updateReady(
            "grading_localizations",
            "question_id",
            question.id,
            targetLanguage,
            sourceHash,
            mapOf(
                "feedback" to result.fields["feedback"],
                "explanation" to result.fields["explanation"],
                "assessment_json" to result.fields["assessmentJson"],
            ),
            result.provider,
            now,
        ),
    )

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
            LocalizableContentType.QUESTION ->
                listOf(Triple("question_localizations", "question_id", event.sourceHash))
            LocalizableContentType.ANSWER ->
                listOf(Triple("answer_localizations", "question_id", event.sourceHash))
            LocalizableContentType.AI_RESPONSE ->
                listOf(Triple("grading_localizations", "question_id", event.sourceHash))
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
        retryPendingBefore: Instant,
    ): String? {
        val requestToken = UUID.randomUUID().toString()
        val columns = values.keys.joinToString(", ")
        val markers = values.keys.joinToString(", ") { ":$it" }
        val refreshCondition =
            """
            source_hash <> values(source_hash)
                or source_language <> values(source_language)
                or (
                    status in ('PENDING', 'FAILED')
                    and updated_at <= :retryPendingBefore
                )
            """.trimIndent()
        val refreshAssignments = values.keys.joinToString(",\n") { column ->
            "$column = if(request_token = values(request_token), values($column), $column)"
        }
        var spec = databaseClient.sql(
            """
            insert into $table (
                $idColumn, target_language, source_language, source_hash,
                $columns, status, translation_version, request_token, created_at, updated_at
            ) values (
                :id, :targetLanguage, :sourceLanguage, :sourceHash,
                $markers, 'PENDING', 1, :requestToken, :now, :now
            )
            on duplicate key update
                request_token = if($refreshCondition, values(request_token), request_token),
                $refreshAssignments,
                status = if(request_token = values(request_token), 'PENDING', status),
                error = if(request_token = values(request_token), null, error),
                updated_at = if(request_token = values(request_token), values(updated_at), updated_at),
                source_language = if(request_token = values(request_token), values(source_language), source_language),
                source_hash = if(request_token = values(request_token), values(source_hash), source_hash)
            """.trimIndent(),
        )
            .bind("id", id)
            .bind("targetLanguage", targetLanguage)
            .bind("sourceLanguage", QuestionLanguage.normalize(sourceLanguage))
            .bind("sourceHash", sourceHash)
            .bind("requestToken", requestToken)
            .bind("now", now)
            .bind("retryPendingBefore", retryPendingBefore)
        values.forEach { (name, value) ->
            spec = if (value == null) spec.bindNull(name, String::class.java) else spec.bind(name, value)
        }
        spec.fetch().rowsUpdated().awaitSingle()
        return databaseClient.sql(
            """
            select status, source_hash, request_token
            from $table
            where $idColumn = :id and target_language = :targetLanguage
            """.trimIndent(),
        )
            .bind("id", id)
            .bind("targetLanguage", targetLanguage)
            .map { row, _ ->
                row.get("status", String::class.java) == "PENDING" &&
                    row.get("source_hash", String::class.java) == sourceHash &&
                    row.get("request_token", String::class.java) == requestToken
            }
            .one()
            .awaitSingleOrNull()
            ?.takeIf { it }
            ?.let { requestToken }
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
                error = null, request_token = null, updated_at = :now
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

    private suspend fun saveReadyAndRefreshQuestion(questionId: Long, saved: Boolean): Boolean {
        if (saved) searchProjection.refresh(questionId)
        return saved
    }

    private fun String.camelKey(): String =
        split('_').let { parts ->
            parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
        }
}
