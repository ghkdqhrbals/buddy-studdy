package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.study.application.model.AnswerGradingProgress
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.backend.study.application.port.outbound.AnswerGradingProgressPort
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class AnswerGradingProgressRepository(
    private val databaseClient: DatabaseClient,
) : AnswerGradingProgressPort {
    override suspend fun append(
        recordId: Long,
        userId: Long,
        requestId: String,
        status: AnswerGradingStatus,
        questionStatus: QuestionStatus,
        errorMessage: String?,
        occurredAt: Instant,
    ): AnswerGradingProgress {
        var insert = databaseClient.sql(
            """
            insert into question_grading_events
                (question_id, user_id, request_id, status, question_status, error_message, created_at)
            values (:questionId, :userId, :requestId, :status, :questionStatus, :errorMessage, :createdAt)
            on duplicate key update id = id
            """.trimIndent(),
        )
            .bind("questionId", recordId)
            .bind("userId", userId)
            .bind("requestId", requestId)
            .bind("status", status.name)
            .bind("questionStatus", questionStatus.databaseValue)
            .bind("createdAt", occurredAt.toUtcLocalDateTime())
        insert = if (errorMessage == null) {
            insert.bindNull("errorMessage", String::class.java)
        } else {
            insert.bind("errorMessage", errorMessage.take(255))
        }
        insert.fetch().rowsUpdated().awaitSingle()

        return databaseClient.sql(
            """
            select id, question_id, request_id, status, question_status, error_message, created_at
            from question_grading_events
            where request_id = :requestId and status = :status
            """.trimIndent(),
        )
            .bind("requestId", requestId)
            .bind("status", status.name)
            .map { row, _ -> row.toProgress() }
            .one()
            .awaitSingleOrNull()
            ?: error("Grading progress event was not persisted.")
    }

    override suspend fun findAfter(
        recordId: Long,
        userId: Long,
        requestId: String,
        afterId: Long,
        limit: Int,
    ): List<AnswerGradingProgress> =
        databaseClient.sql(
            """
            select id, question_id, request_id, status, question_status, error_message, created_at
            from question_grading_events
            where question_id = :questionId
              and user_id = :userId
              and request_id = :requestId
              and id > :afterId
            order by id asc
            limit :limit
            """.trimIndent(),
        )
            .bind("questionId", recordId)
            .bind("userId", userId)
            .bind("requestId", requestId)
            .bind("afterId", afterId)
            .bind("limit", limit.coerceIn(1, 100))
            .map { row, _ -> row.toProgress() }
            .all()
            .asFlow()
            .toList()

    private fun io.r2dbc.spi.Row.toProgress() = AnswerGradingProgress(
        id = get("id", java.lang.Long::class.java)!!.toLong(),
        recordId = get("question_id", java.lang.Long::class.java)!!.toLong(),
        requestId = get("request_id", String::class.java)!!,
        status = AnswerGradingStatus.valueOf(get("status", String::class.java)!!),
        questionStatus = QuestionStatus.fromDatabaseValue(get("question_status", String::class.java)!!),
        errorMessage = get("error_message", String::class.java),
        occurredAt = get("created_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
    )

    private fun Instant.toUtcLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
