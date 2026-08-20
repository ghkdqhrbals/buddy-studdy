package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.common.adapter.outbound.persistence.bindIndexed
import com.buddystudy.backend.common.adapter.outbound.persistence.indexedBindMarkers
import com.buddystudy.backend.study.application.model.QuestionGenerationSaga
import com.buddystudy.backend.study.application.model.QuestionGenerationSource
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.dao.DuplicateKeyException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class QuestionGenerationSagaRepository(
    private val databaseClient: DatabaseClient,
) : QuestionGenerationSagaPort {
    override suspend fun insert(saga: QuestionGenerationSaga): Boolean = try {
        var statement = databaseClient.sql(
            """
            insert into question_generation_sagas (
                correlation_id, user_id, study_id, topic_id, question_id, source, status, current_step,
                idempotency_key, quota_period_started_at, quota_refunded_at, failed_step, error_code,
                error_message, created_at, updated_at, completed_at, rollback_completed_at
            ) values (
                :correlationId, :userId, :studyId, :topicId, :questionId, :source, :status, :currentStep,
                :idempotencyKey, :quotaPeriodStartedAt, :quotaRefundedAt, :failedStep, :errorCode,
                :errorMessage, :createdAt, :updatedAt, :completedAt, :rollbackCompletedAt
            )
            """.trimIndent(),
        )
            .bind("correlationId", saga.correlationId)
            .bind("userId", saga.userId)
            .bind("studyId", saga.studyId)
            .bind("topicId", saga.topicId)
            .bind("source", saga.source.name)
            .bind("status", saga.status.name)
            .bind("currentStep", saga.currentStep.name)
            .bind("idempotencyKey", saga.idempotencyKey)
            .bind("quotaPeriodStartedAt", saga.quotaPeriodStartedAt.utcDateTime())
            .bind("createdAt", saga.createdAt.utcDateTime())
            .bind("updatedAt", saga.updatedAt.utcDateTime())
        statement = statement.bindNullable("questionId", saga.questionId, Long::class.javaObjectType)
            .bindNullable("quotaRefundedAt", saga.quotaRefundedAt?.utcDateTime(), LocalDateTime::class.java)
            .bindNullable("failedStep", saga.failedStep?.name, String::class.java)
            .bindNullable("errorCode", saga.errorCode, String::class.java)
            .bindNullable("errorMessage", saga.errorMessage, String::class.java)
            .bindNullable("completedAt", saga.completedAt?.utcDateTime(), LocalDateTime::class.java)
            .bindNullable("rollbackCompletedAt", saga.rollbackCompletedAt?.utcDateTime(), LocalDateTime::class.java)
        statement.fetch().rowsUpdated().awaitSingle() == 1L
    } catch (_: DuplicateKeyException) {
        false
    }

    override suspend fun findByCorrelationId(correlationId: String): QuestionGenerationSaga? =
        select("correlation_id = :value", correlationId)

    override suspend fun findByUserIdAndIdempotencyKey(userId: Long, idempotencyKey: String): QuestionGenerationSaga? =
        databaseClient.sql(
            """
            select *
            from question_generation_sagas
            where user_id = :userId and idempotency_key = :idempotencyKey
            limit 1
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("idempotencyKey", idempotencyKey)
            .map { row, _ -> row.toSaga() }
            .one()
            .awaitSingleOrNull()

    override suspend fun findActiveByUserIdAndTopicId(userId: Long, topicId: Long): QuestionGenerationSaga? =
        databaseClient.sql(
            """
            select *
            from question_generation_sagas
            where user_id = :userId
              and topic_id = :topicId
              and (
                  status in ('QUEUED', 'GENERATING', 'TRANSLATING')
                  or (status = 'FAILED' and rollback_completed_at is null)
              )
            limit 1
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("topicId", topicId)
            .map { row, _ -> row.toSaga() }
            .one()
            .awaitSingleOrNull()

    override suspend fun findActiveTopicIdsByUserId(userId: Long, topicIds: Collection<Long>): Set<Long> {
        if (topicIds.isEmpty()) return emptySet()
        val distinctTopicIds = topicIds.distinct()
        val topicMarkers = indexedBindMarkers("topicId", distinctTopicIds.size)
        return databaseClient.sql(
            """
            select distinct topic_id
            from question_generation_sagas
            where user_id = :userId
              and topic_id in ($topicMarkers)
              and (
                  status in ('QUEUED', 'GENERATING', 'TRANSLATING')
                  or (status = 'FAILED' and rollback_completed_at is null)
              )
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bindIndexed("topicId", distinctTopicIds)
            .map { row, _ -> row.get("topic_id", java.lang.Long::class.java)!!.toLong() }
            .all()
            .collectList()
            .awaitSingle()
            .toSet()
    }

    override suspend fun markGenerating(correlationId: String, now: Instant): Boolean =
        updateState(
            correlationId = correlationId,
            expectedStatus = QuestionGenerationStatus.QUEUED,
            status = QuestionGenerationStatus.GENERATING,
            step = QuestionGenerationStep.GENERATING,
            now = now,
        )

    override suspend fun markTranslating(correlationId: String, questionId: Long, now: Instant): Boolean =
        databaseClient.sql(
            """
            update question_generation_sagas
            set question_id = :questionId,
                status = :status,
                current_step = :currentStep,
                updated_at = :updatedAt
            where correlation_id = :correlationId
              and status = :expectedStatus
              and question_id is null
            """.trimIndent(),
        )
            .bind("questionId", questionId)
            .bind("status", QuestionGenerationStatus.TRANSLATING.name)
            .bind("currentStep", QuestionGenerationStep.TRANSLATING.name)
            .bind("updatedAt", now.utcDateTime())
            .bind("correlationId", correlationId)
            .bind("expectedStatus", QuestionGenerationStatus.GENERATING.name)
            .fetch().rowsUpdated().awaitSingle() > 0

    override suspend fun markCompleted(correlationId: String, now: Instant): Boolean =
        databaseClient.sql(
            """
            update question_generation_sagas
            set status = :status,
                current_step = :currentStep,
                completed_at = :completedAt,
                updated_at = :updatedAt
            where correlation_id = :correlationId
              and status = :expectedStatus
            """.trimIndent(),
        )
            .bind("status", QuestionGenerationStatus.COMPLETED.name)
            .bind("currentStep", QuestionGenerationStep.COMPLETED.name)
            .bind("completedAt", now.utcDateTime())
            .bind("updatedAt", now.utcDateTime())
            .bind("correlationId", correlationId)
            .bind("expectedStatus", QuestionGenerationStatus.TRANSLATING.name)
            .fetch().rowsUpdated().awaitSingle() > 0

    override suspend fun markFailed(
        correlationId: String,
        failedStep: QuestionGenerationStep,
        errorCode: String,
        errorMessage: String,
        refundedAt: Instant?,
        now: Instant,
    ): Boolean {
        var statement = databaseClient.sql(
            """
            update question_generation_sagas
            set status = :status,
                failed_step = :failedStep,
                error_code = :errorCode,
                error_message = :errorMessage,
                quota_refunded_at = coalesce(quota_refunded_at, :refundedAt),
                updated_at = :updatedAt
            where correlation_id = :correlationId
              and status not in ('COMPLETED', 'FAILED')
            """.trimIndent(),
        )
            .bind("status", QuestionGenerationStatus.FAILED.name)
            .bind("failedStep", failedStep.name)
            .bind("errorCode", errorCode.take(80))
            .bind("errorMessage", errorMessage.take(1000))
            .bind("updatedAt", now.utcDateTime())
            .bind("correlationId", correlationId)
        statement = statement.bindNullable("refundedAt", refundedAt?.utcDateTime(), LocalDateTime::class.java)
        return statement.fetch().rowsUpdated().awaitSingle() > 0
    }

    override suspend fun markRollbackCompleted(correlationId: String, now: Instant): Boolean =
        databaseClient.sql(
            """
            update question_generation_sagas
            set quota_refunded_at = coalesce(quota_refunded_at, :completedAt),
                rollback_completed_at = :completedAt,
                updated_at = :completedAt
            where correlation_id = :correlationId
              and status = 'FAILED'
              and rollback_completed_at is null
            """.trimIndent(),
        )
            .bind("completedAt", now.utcDateTime())
            .bind("correlationId", correlationId)
            .fetch().rowsUpdated().awaitSingle() == 1L

    private suspend fun select(whereClause: String, value: String): QuestionGenerationSaga? =
        databaseClient.sql(
            """
            select *
            from question_generation_sagas
            where $whereClause
            limit 1
            """.trimIndent(),
        )
            .bind("value", value)
            .map { row, _ -> row.toSaga() }
            .one()
            .awaitSingleOrNull()

    private suspend fun updateState(
        correlationId: String,
        expectedStatus: QuestionGenerationStatus,
        status: QuestionGenerationStatus,
        step: QuestionGenerationStep,
        now: Instant,
    ): Boolean =
        databaseClient.sql(
            """
            update question_generation_sagas
            set status = :status, current_step = :currentStep, updated_at = :updatedAt
            where correlation_id = :correlationId and status = :expectedStatus
            """.trimIndent(),
        )
            .bind("status", status.name)
            .bind("currentStep", step.name)
            .bind("updatedAt", now.utcDateTime())
            .bind("correlationId", correlationId)
            .bind("expectedStatus", expectedStatus.name)
            .fetch().rowsUpdated().awaitSingle() > 0

    private fun Row.toSaga(): QuestionGenerationSaga =
        QuestionGenerationSaga(
            correlationId = get("correlation_id", String::class.java)!!,
            userId = get("user_id", java.lang.Long::class.java)!!.toLong(),
            studyId = get("study_id", java.lang.Long::class.java)!!.toLong(),
            topicId = get("topic_id", java.lang.Long::class.java)!!.toLong(),
            questionId = get("question_id", java.lang.Long::class.java)?.toLong(),
            source = QuestionGenerationSource.valueOf(get("source", String::class.java)!!),
            status = QuestionGenerationStatus.valueOf(get("status", String::class.java)!!),
            currentStep = QuestionGenerationStep.valueOf(get("current_step", String::class.java)!!),
            idempotencyKey = get("idempotency_key", String::class.java)!!,
            quotaPeriodStartedAt = get("quota_period_started_at", LocalDateTime::class.java)!!.utcInstant(),
            quotaRefundedAt = get("quota_refunded_at", LocalDateTime::class.java)?.utcInstant(),
            failedStep = get("failed_step", String::class.java)?.let(QuestionGenerationStep::valueOf),
            errorCode = get("error_code", String::class.java),
            errorMessage = get("error_message", String::class.java),
            createdAt = get("created_at", LocalDateTime::class.java)!!.utcInstant(),
            updatedAt = get("updated_at", LocalDateTime::class.java)!!.utcInstant(),
            completedAt = get("completed_at", LocalDateTime::class.java)?.utcInstant(),
            rollbackCompletedAt = get("rollback_completed_at", LocalDateTime::class.java)?.utcInstant(),
        )

    private fun Instant.utcDateTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
    private fun LocalDateTime.utcInstant(): Instant = toInstant(ZoneOffset.UTC)
}

private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?,
    type: Class<T>,
): DatabaseClient.GenericExecuteSpec =
    if (value == null) bindNull(name, type) else bind(name, value)
