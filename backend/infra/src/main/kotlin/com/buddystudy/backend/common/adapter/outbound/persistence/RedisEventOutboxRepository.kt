package com.buddystudy.backend.common.adapter.outbound.persistence

import com.buddystudy.backend.common.application.outbox.ClaimedRedisOutboxEvent
import com.buddystudy.backend.common.application.outbox.QuestionCreatedOutboxEvent
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxPort
import com.buddystudy.backend.common.application.outbox.RedisOutboxEventType
import com.buddystudy.backend.jooq.tables.RedisEventOutbox.REDIS_EVENT_OUTBOX
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Component
class RedisEventOutboxRepository(
    private val jooq: JooqR2dbcExecutor,
    private val objectMapper: ObjectMapper,
) : RedisEventOutboxPort {
    override suspend fun appendQuestionCreated(event: QuestionCreatedOutboxEvent): Long =
        append(
            eventId = event.eventId,
            eventType = RedisOutboxEventType.QUESTION_CREATED,
            payloadJson = objectMapper.writeValueAsString(
                QuestionCreatedPayload(
                    questionId = event.questionId,
                    language = event.language,
                    createdAt = event.createdAt,
                ),
            ),
            createdAt = event.createdAt,
        )

    override suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant): Long =
        append(
            eventId = command.eventId,
            eventType = RedisOutboxEventType.NOTIFICATION_REQUESTED,
            payloadJson = objectMapper.writeValueAsString(command),
            createdAt = createdAt,
        )

    @Transactional
    override suspend fun claimBatch(
        now: Instant,
        staleBefore: Instant,
        limit: Int,
    ): List<ClaimedRedisOutboxEvent> = jooq.withDsl { dsl ->
        if (limit <= 0) return@withDsl emptyList()
        val table = REDIS_EVENT_OUTBOX
        val nowOffset = now.toOffsetDateTime()
        val ids = dsl
            .select(table.ID)
            .from(table)
            .where(
                table.STATUS.eq(PENDING)
                    .and(table.NEXT_ATTEMPT_AT.le(nowOffset))
                    .or(table.STATUS.eq(PROCESSING).and(table.CLAIMED_AT.le(staleBefore.toOffsetDateTime()))),
            )
            .orderBy(table.CREATED_AT.asc(), table.ID.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .asFlow()
            .toList()
            .map { it.value1() }

        if (ids.isEmpty()) return@withDsl emptyList()

        dsl.update(table)
            .set(table.STATUS, PROCESSING)
            .set(table.CLAIMED_AT, nowOffset)
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
                    createdAt = record.createdAt.toInstant(),
                )
            }
    }

    override suspend fun markPublished(id: Long, publishedAt: Instant): Boolean = jooq.withDsl { dsl ->
        val table = REDIS_EVENT_OUTBOX
        val now = publishedAt.toOffsetDateTime()
        dsl.update(table)
            .set(table.STATUS, PUBLISHED)
            .set(table.PUBLISHED_AT, now)
            .setNull(table.CLAIMED_AT)
            .setNull(table.LAST_ERROR)
            .set(table.UPDATED_AT, now)
            .where(table.ID.eq(id).and(table.STATUS.eq(PROCESSING)))
            .awaitFirst() == 1
    }

    override suspend fun markRetry(
        id: Long,
        attempts: Int,
        nextAttemptAt: Instant,
        error: String,
        updatedAt: Instant,
    ): Boolean = jooq.withDsl { dsl ->
        val table = REDIS_EVENT_OUTBOX
        dsl.update(table)
            .set(table.STATUS, PENDING)
            .set(table.ATTEMPTS, attempts)
            .set(table.NEXT_ATTEMPT_AT, nextAttemptAt.toOffsetDateTime())
            .setNull(table.CLAIMED_AT)
            .set(table.LAST_ERROR, error.take(MAX_ERROR_LENGTH))
            .set(table.UPDATED_AT, updatedAt.toOffsetDateTime())
            .where(table.ID.eq(id).and(table.STATUS.eq(PROCESSING)))
            .awaitFirst() == 1
    }

    private suspend fun append(
        eventId: String,
        eventType: RedisOutboxEventType,
        payloadJson: String,
        createdAt: Instant,
    ): Long = jooq.withDsl { dsl ->
        val table = REDIS_EVENT_OUTBOX
        val now = createdAt.toOffsetDateTime()
        val inserted = dsl.insertInto(table)
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
            .returning(table.ID)
            .awaitFirstOrNull()
            ?.id
        inserted ?: dsl.select(table.ID)
                .from(table)
                .where(table.EVENT_TYPE.eq(eventType.name).and(table.EVENT_ID.eq(eventId)))
                .awaitFirst()
                .value1()
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private data class QuestionCreatedPayload(
        val questionId: Long,
        val language: String,
        val createdAt: Instant,
    )

    private companion object {
        const val PENDING = "PENDING"
        const val PROCESSING = "PROCESSING"
        const val PUBLISHED = "PUBLISHED"
        const val PAYLOAD_VERSION = 1
        const val MAX_ERROR_LENGTH = 4_000
    }
}
