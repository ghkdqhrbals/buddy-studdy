package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.config.saveEntity
import com.buddystudy.backend.study.application.content.QuestionNotificationContentPolicy
import com.buddystudy.backend.study.application.port.outbound.ClaimedQuestionPushOutbox
import com.buddystudy.backend.study.application.port.outbound.QuestionPushOutboxPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystudy.study.domain.entity.QuestionPushOutboxEntity
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class QuestionPushOutboxRepository(
    private val template: R2dbcEntityTemplate,
) : QuestionPushOutboxPort {
    override suspend fun enqueue(request: QuestionPushRequest, now: Instant): Long = save(
        QuestionPushOutboxEntity(
            recordId = request.recordId,
            studyId = request.studyId,
            deviceId = request.deviceId,
            userId = request.userId,
            question = request.question,
            expectedAnswerHint = request.expectedAnswerHint,
            topic = request.topic,
            difficultyLevel = request.difficultyLevel,
            language = request.language,
            sound = request.sound,
            intervalMinutes = request.intervalMinutes,
            status = PENDING,
            attempts = 0,
            nextAttemptAt = now,
            createdAt = request.createdAt,
            updatedAt = now,
        ),
    ).id

    override suspend fun claim(id: Long, now: Instant, staleBefore: Instant): ClaimedQuestionPushOutbox? {
        val claimToken = UUID.randomUUID().toString()
        val updated = template.databaseClient.sql(
            """
            update question_push_outbox
            set status = :processing,
                claimed_at = :now,
                claim_token = :claimToken,
                updated_at = :now
            where id = :id
              and (
                (status = :pending and next_attempt_at <= :now)
                or (status = :processing and claimed_at <= :staleBefore)
              )
            """.trimIndent(),
        )
            .bind("processing", PROCESSING)
            .bind("now", now)
            .bind("claimToken", claimToken)
            .bind("id", id)
            .bind("pending", PENDING)
            .bind("staleBefore", staleBefore)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        if (updated != 1L) return null
        return findById(id)?.toClaimed(claimToken)
    }

    override suspend fun claimBatch(
        now: Instant,
        staleBefore: Instant,
        limit: Int,
    ): List<ClaimedQuestionPushOutbox> {
        if (limit <= 0) return emptyList()
        val candidates = template.databaseClient.sql(
            """
            select id from question_push_outbox
            where (status = :pending and next_attempt_at <= :now)
               or (status = :processing and claimed_at <= :staleBefore)
            order by created_at asc, id asc
            limit :candidateLimit
            """.trimIndent(),
        )
            .bind("pending", PENDING)
            .bind("processing", PROCESSING)
            .bind("now", now)
            .bind("staleBefore", staleBefore)
            .bind("candidateLimit", limit * 2)
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .all()
            .collectList()
            .awaitSingle()

        val claimed = ArrayList<ClaimedQuestionPushOutbox>(limit)
        for (id in candidates) {
            claim(id, now, staleBefore)?.let(claimed::add)
            if (claimed.size == limit) break
        }
        return claimed
    }

    override suspend fun markPublished(id: Long, claimToken: String, publishedAt: Instant): Boolean =
        updateClaimed(
            id = id,
            claimToken = claimToken,
            sql =
                """
                update question_push_outbox
                set status = :nextStatus,
                    published_at = :updatedAt,
                    claimed_at = null,
                    claim_token = null,
                    last_error = null,
                    updated_at = :updatedAt
                where id = :id and status = :processing and claim_token = :claimToken
                """.trimIndent(),
            nextStatus = PUBLISHED,
            updatedAt = publishedAt,
        )

    override suspend fun markRetry(
        id: Long,
        claimToken: String,
        attempts: Int,
        nextAttemptAt: Instant,
        error: String,
        updatedAt: Instant,
    ): Boolean {
        val updated = template.databaseClient.sql(
            """
            update question_push_outbox
            set status = :pending,
                attempts = :attempts,
                next_attempt_at = :nextAttemptAt,
                claimed_at = null,
                claim_token = null,
                last_error = :error,
                updated_at = :updatedAt
            where id = :id and status = :processing and claim_token = :claimToken
            """.trimIndent(),
        )
            .bind("pending", PENDING)
            .bind("attempts", attempts)
            .bind("nextAttemptAt", nextAttemptAt)
            .bind("error", error.take(MAX_ERROR_LENGTH))
            .bind("updatedAt", updatedAt)
            .bind("id", id)
            .bind("processing", PROCESSING)
            .bind("claimToken", claimToken)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return updated == 1L
    }

    suspend fun findById(id: Long): QuestionPushOutboxEntity? =
        template.selectOne(Query.query(Criteria.where("id").`is`(id)), QuestionPushOutboxEntity::class.java)
            .awaitSingleOrNull()

    suspend fun save(entity: QuestionPushOutboxEntity): QuestionPushOutboxEntity = template.saveEntity(entity, entity.id)

    suspend fun deleteAll(): Long =
        template.delete(QuestionPushOutboxEntity::class.java).all().awaitSingle()

    private suspend fun updateClaimed(
        id: Long,
        claimToken: String,
        sql: String,
        nextStatus: String,
        updatedAt: Instant,
    ): Boolean {
        val updated = template.databaseClient.sql(sql)
            .bind("nextStatus", nextStatus)
            .bind("updatedAt", updatedAt)
            .bind("id", id)
            .bind("processing", PROCESSING)
            .bind("claimToken", claimToken)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return updated == 1L
    }

    private fun QuestionPushOutboxEntity.toClaimed(claimToken: String): ClaimedQuestionPushOutbox =
        ClaimedQuestionPushOutbox(
            id = id,
            request = QuestionPushRequest(
                recordId = recordId,
                studyId = studyId,
                createdAt = createdAt,
                deviceId = deviceId,
                userId = userId,
                question = question,
                expectedAnswerHint = expectedAnswerHint,
                topic = topic,
                difficultyLevel = difficultyLevel,
                language = language,
                sound = sound,
                intervalMinutes = intervalMinutes,
                title = QuestionNotificationContentPolicy.title(language),
                body = QuestionNotificationContentPolicy.preview(question),
                deepLink = "buddystudy://records/$recordId",
            ),
            attempts = attempts,
            createdAt = createdAt,
            claimToken = claimToken,
        )

    private companion object {
        const val PENDING = "PENDING"
        const val PROCESSING = "PROCESSING"
        const val PUBLISHED = "PUBLISHED"
        const val MAX_ERROR_LENGTH = 4_000
    }
}
