package com.buddystudy.backend.admin.stream.adapter.outbound.persistence

import com.buddystudy.backend.admin.stream.application.model.AdminCursorPage
import com.buddystudy.backend.admin.stream.application.model.AdminRedisEventOutboxEntry
import com.buddystudy.backend.admin.stream.application.port.outbound.AdminOutboxInspectionPort
import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class AdminOutboxInspectionAdapter(
    private val database: DatabaseClient,
    private val redactor: SensitiveDataRedactor,
) : AdminOutboxInspectionPort {
    override suspend fun redisEvents(
        cursor: Long?,
        limit: Int,
        status: String?,
        eventType: String?,
    ): AdminCursorPage<AdminRedisEventOutboxEntry> {
        val where = buildList {
            cursor?.let { add("id < :cursor") }
            status?.let { add("status = :status") }
            eventType?.let { add("event_type = :eventType") }
        }.joinToString(prefix = if (cursor != null || status != null || eventType != null) "where " else "", separator = " and ")
        var query = database.sql(
            """
            select
                id, event_id, event_type, stream_key, redis_record_id, payload_version, payload_json, status, attempts,
                next_attempt_at, claimed_at, published_at, last_error, created_at, updated_at
            from redis_event_outbox
            $where
            order by id desc
            limit :fetchLimit
            """.trimIndent(),
        ).bind("fetchLimit", limit + 1)
        cursor?.let { query = query.bind("cursor", it) }
        status?.let { query = query.bind("status", it) }
        eventType?.let { query = query.bind("eventType", it) }
        val entries = query.map { row, _ -> row.toRedisEvent() }.all().collectList().awaitSingle()
        return entries.toCursorPage(limit) { it.id.toString() }
    }

    private fun Row.toRedisEvent(): AdminRedisEventOutboxEntry =
        AdminRedisEventOutboxEntry(
            id = long("id"),
            eventId = string("event_id"),
            eventType = string("event_type"),
            streamKey = nullableString("stream_key"),
            redisRecordId = nullableString("redis_record_id"),
            payloadVersion = int("payload_version"),
            payloadJson = redactor.json(string("payload_json")),
            status = string("status"),
            attempts = int("attempts"),
            nextAttemptAt = instant("next_attempt_at"),
            claimedAt = nullableInstant("claimed_at"),
            publishedAt = nullableInstant("published_at"),
            lastError = nullableString("last_error"),
            createdAt = instant("created_at"),
            updatedAt = instant("updated_at"),
        )

    private fun <T> List<T>.toCursorPage(limit: Int, cursorOf: (T) -> String): AdminCursorPage<T> =
        AdminCursorPage(
            items = take(limit),
            nextCursor = take(limit).lastOrNull()?.let(cursorOf)?.takeIf { size > limit },
            hasMore = size > limit,
            limit = limit,
        )

    private fun Row.string(name: String): String = get(name, String::class.java).orEmpty()
    private fun Row.nullableString(name: String): String? = get(name, String::class.java)
    private fun Row.int(name: String): Int = get(name, java.lang.Integer::class.java)?.toInt() ?: 0
    private fun Row.long(name: String): Long = get(name, java.lang.Long::class.java)?.toLong() ?: 0
    private fun Row.nullableLong(name: String): Long? = get(name, java.lang.Long::class.java)?.toLong()
    private fun Row.instant(name: String): Instant = nullableInstant(name) ?: Instant.EPOCH
    private fun Row.nullableInstant(name: String): Instant? =
        get(name, Instant::class.java)
            ?: get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
}
