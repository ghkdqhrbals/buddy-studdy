package com.buddystudy.backend.common.adapter.outbound.persistence

import com.buddystudy.backend.common.application.outbox.ClaimedRedisOutboxEvent
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxPort
import com.buddystudy.backend.common.application.outbox.RedisOutboxEventType
import com.buddystudy.backend.jooq.tables.RedisEventOutbox.REDIS_EVENT_OUTBOX
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.profile.application.model.AccountWithdrawnEvent
import com.buddystudy.backend.profile.application.port.outbound.AccountWithdrawalEventPort
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Component
class RedisEventOutboxRepository(
    private val jooq: JooqR2dbcExecutor,
    private val objectMapper: ObjectMapper,
) : RedisEventOutboxPort, AccountWithdrawalEventPort {
    override suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant): Long =
        append(
            eventId = command.eventId,
            eventType = RedisOutboxEventType.NOTIFICATION_REQUESTED,
            payloadJson = objectMapper.writeValueAsString(command),
            createdAt = createdAt,
        )

    override suspend fun appendAnswerGrading(event: AnswerGradingRequestedEvent, createdAt: Instant): Long =
        append(
            eventId = event.eventId,
            eventType = RedisOutboxEventType.ANSWER_GRADING_REQUESTED,
            payloadJson = objectMapper.writeValueAsString(event),
            createdAt = createdAt,
        )

    override suspend fun appendQuestionGenerated(event: QuestionGeneratedEvent, createdAt: Instant): Long =
        append(
            eventId = event.eventId,
            eventType = RedisOutboxEventType.QUESTION_GENERATED,
            payloadJson = objectMapper.writeValueAsString(event),
            createdAt = createdAt,
        )

    override suspend fun appendQuestionGenerationRequested(
        event: QuestionGenerationRequestedEvent,
        createdAt: Instant,
    ): Long =
        append(
            eventId = event.eventId,
            eventType = RedisOutboxEventType.QUESTION_GENERATION_REQUESTED,
            payloadJson = objectMapper.writeValueAsString(event),
            createdAt = createdAt,
        )

    override suspend fun append(event: AccountWithdrawnEvent): Long =
        append(
            eventId = event.eventId,
            eventType = RedisOutboxEventType.ACCOUNT_WITHDRAWN,
            payloadJson = objectMapper.writeValueAsString(event),
            createdAt = event.withdrawnAt,
        )

    @Transactional
    override suspend fun claim(
        id: Long,
        now: Instant,
        staleBefore: Instant,
    ): ClaimedRedisOutboxEvent? = jooq.withDsl { dsl ->
        val table = REDIS_EVENT_OUTBOX
        val nowOffset = now.toUtcLocalDateTime()
        val claimedId = dsl
            .select(table.ID)
            .from(table)
            .where(
                table.ID.eq(id).and(
                    table.STATUS.eq(PENDING)
                        .and(table.NEXT_ATTEMPT_AT.le(nowOffset))
                        .or(table.STATUS.eq(PROCESSING).and(table.CLAIMED_AT.le(staleBefore.toUtcLocalDateTime()))),
                ),
            )
            .forUpdate()
            .skipLocked()
            .asFlow()
            .toList()
            .firstOrNull()
            ?.value1()
            ?: return@withDsl null
        val claimToken = UUID.randomUUID().toString()

        dsl.update(table)
            .set(table.STATUS, PROCESSING)
            .set(table.CLAIMED_AT, nowOffset)
            .set(table.CLAIM_TOKEN, claimToken)
            .set(table.UPDATED_AT, nowOffset)
            .where(table.ID.eq(claimedId))
            .awaitFirst()

        dsl.selectFrom(table)
            .where(table.ID.eq(claimedId))
            .asFlow()
            .toList()
            .firstOrNull()
            ?.let { record ->
                ClaimedRedisOutboxEvent(
                    id = record.id,
                    eventId = record.eventId,
                    eventType = RedisOutboxEventType.valueOf(record.eventType),
                    payloadVersion = record.payloadVersion,
                    payloadJson = record.payloadJson,
                    attempts = record.attempts,
                    createdAt = record.createdAt.toInstant(ZoneOffset.UTC),
                    claimToken = claimToken,
                )
            }
    }

    @Transactional
    override suspend fun claimBatch(
        now: Instant,
        staleBefore: Instant,
        limit: Int,
    ): List<ClaimedRedisOutboxEvent> = jooq.withDsl { dsl ->
        if (limit <= 0) return@withDsl emptyList()
        val table = REDIS_EVENT_OUTBOX
        val nowOffset = now.toUtcLocalDateTime()
        val ids = dsl
            .select(table.ID)
            .from(table)
            .where(
                table.STATUS.eq(PENDING)
                    .and(table.NEXT_ATTEMPT_AT.le(nowOffset))
                    .or(table.STATUS.eq(PROCESSING).and(table.CLAIMED_AT.le(staleBefore.toUtcLocalDateTime()))),
            )
            .orderBy(table.CREATED_AT.asc(), table.ID.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .asFlow()
            .toList()
            .map { it.value1() }

        if (ids.isEmpty()) return@withDsl emptyList()
        val claimToken = UUID.randomUUID().toString()

        dsl.update(table)
            .set(table.STATUS, PROCESSING)
            .set(table.CLAIMED_AT, nowOffset)
            .set(table.CLAIM_TOKEN, claimToken)
            .set(table.UPDATED_AT, nowOffset)
            .where(table.ID.`in`(ids))
            .awaitFirst()

        dsl.selectFrom(table)
            .where(table.ID.`in`(ids))
            .orderBy(table.CREATED_AT.asc(), table.ID.asc())
            .asFlow()
            .toList()
            .map { record ->
                ClaimedRedisOutboxEvent(
                    id = record.id,
                    eventId = record.eventId,
                    eventType = RedisOutboxEventType.valueOf(record.eventType),
                    payloadVersion = record.payloadVersion,
                    payloadJson = record.payloadJson,
                    attempts = record.attempts,
                    createdAt = record.createdAt.toInstant(ZoneOffset.UTC),
                    claimToken = claimToken,
                )
            }
    }

    override suspend fun markPublished(id: Long, claimToken: String, publishedAt: Instant): Boolean = jooq.withDsl { dsl ->
        val table = REDIS_EVENT_OUTBOX
        val now = publishedAt.toUtcLocalDateTime()
        dsl.update(table)
            .set(table.STATUS, PUBLISHED)
            .set(table.PUBLISHED_AT, now)
            .setNull(table.CLAIMED_AT)
            .setNull(table.CLAIM_TOKEN)
            .setNull(table.LAST_ERROR)
            .set(table.UPDATED_AT, now)
            .where(
                table.ID.eq(id)
                    .and(table.STATUS.eq(PROCESSING))
                    .and(table.CLAIM_TOKEN.eq(claimToken)),
            )
            .awaitFirst() == 1
    }

    override suspend fun markRetry(
        id: Long,
        claimToken: String,
        attempts: Int,
        nextAttemptAt: Instant,
        error: String,
        updatedAt: Instant,
    ): Boolean = jooq.withDsl { dsl ->
        val table = REDIS_EVENT_OUTBOX
        dsl.update(table)
            .set(table.STATUS, PENDING)
            .set(table.ATTEMPTS, attempts)
            .set(table.NEXT_ATTEMPT_AT, nextAttemptAt.toUtcLocalDateTime())
            .setNull(table.CLAIMED_AT)
            .setNull(table.CLAIM_TOKEN)
            .set(table.LAST_ERROR, error.take(MAX_ERROR_LENGTH))
            .set(table.UPDATED_AT, updatedAt.toUtcLocalDateTime())
            .where(
                table.ID.eq(id)
                    .and(table.STATUS.eq(PROCESSING))
                    .and(table.CLAIM_TOKEN.eq(claimToken)),
            )
            .awaitFirst() == 1
    }

    private suspend fun append(
        eventId: String,
        eventType: RedisOutboxEventType,
        payloadJson: String,
        createdAt: Instant,
    ): Long = jooq.withDsl { dsl ->
        val table = REDIS_EVENT_OUTBOX
        val now = createdAt.toUtcLocalDateTime()
        dsl.insertInto(table)
            .set(table.EVENT_ID, eventId)
            .set(table.EVENT_TYPE, eventType.name)
            .set(table.PAYLOAD_VERSION, PAYLOAD_VERSION)
            .set(table.PAYLOAD_JSON, payloadJson)
            .set(table.STATUS, PENDING)
            .set(table.ATTEMPTS, 0)
            .set(table.NEXT_ATTEMPT_AT, now)
            .set(table.CREATED_AT, now)
            .set(table.UPDATED_AT, now)
            .onConflict(table.EVENT_TYPE, table.EVENT_ID)
            .doNothing()
            .awaitFirst()

        dsl.select(table.ID)
            .from(table)
            .where(table.EVENT_TYPE.eq(eventType.name).and(table.EVENT_ID.eq(eventId)))
            .awaitFirst()
            .value1()
    }

    private fun Instant.toUtcLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val PENDING = "PENDING"
        const val PROCESSING = "PROCESSING"
        const val PUBLISHED = "PUBLISHED"
        const val PAYLOAD_VERSION = 1
        const val MAX_ERROR_LENGTH = 4_000
    }
}
