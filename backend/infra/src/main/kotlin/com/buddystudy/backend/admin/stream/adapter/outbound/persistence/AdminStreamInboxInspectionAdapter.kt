package com.buddystudy.backend.admin.stream.adapter.outbound.persistence

import com.buddystudy.backend.admin.stream.application.model.AdminCursorPage
import com.buddystudy.backend.admin.stream.application.model.AdminStreamInboxAttempt
import com.buddystudy.backend.admin.stream.application.port.outbound.AdminStreamInboxInspectionPort
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class AdminStreamInboxInspectionAdapter(
    private val database: DatabaseClient,
) : AdminStreamInboxInspectionPort {
    override suspend fun attempts(
        cursor: Long?,
        limit: Int,
        consumerGroup: String?,
        status: String?,
        query: String?,
    ): AdminCursorPage<AdminStreamInboxAttempt> {
        val where = buildList {
            cursor?.let { add("id < :cursor") }
            consumerGroup?.let { add("consumer_group = :consumerGroup") }
            status?.let { add("status = :status") }
            query?.let {
                add(
                    """
                    (
                        event_id like :query
                        or correlation_id like :query
                        or coalesce(error_type, '') like :query
                        or coalesce(error_message, '') like :query
                    )
                    """.trimIndent(),
                )
            }
        }.joinToString(
            prefix = if (cursor != null || consumerGroup != null || status != null || query != null) "where " else "",
            separator = " and ",
        )
        var statement = database.sql(
            """
            select
                id, event_id, consumer_group, correlation_id, attempt, status,
                error_type, error_message, started_at, finished_at
            from stream_consumer_inbox_attempts
            $where
            order by id desc
            limit :fetchLimit
            """.trimIndent(),
        ).bind("fetchLimit", limit + 1)
        cursor?.let { statement = statement.bind("cursor", it) }
        consumerGroup?.let { statement = statement.bind("consumerGroup", it) }
        status?.let { statement = statement.bind("status", it) }
        query?.let { statement = statement.bind("query", "%$it%") }

        val rows = statement
            .map { row, _ -> row.toAttempt() }
            .all()
            .collectList()
            .awaitSingle()
        val items = rows.take(limit)
        return AdminCursorPage(
            items = items,
            nextCursor = items.lastOrNull()?.id?.toString()?.takeIf { rows.size > limit },
            hasMore = rows.size > limit,
            limit = limit,
        )
    }

    private fun Row.toAttempt(): AdminStreamInboxAttempt {
        val startedAt = instant("started_at")
        val finishedAt = nullableInstant("finished_at")
        return AdminStreamInboxAttempt(
            id = long("id"),
            eventId = string("event_id"),
            consumerGroup = string("consumer_group"),
            correlationId = string("correlation_id"),
            attempt = int("attempt"),
            status = string("status"),
            errorType = nullableString("error_type"),
            errorMessage = nullableString("error_message"),
            startedAt = startedAt,
            finishedAt = finishedAt,
            durationMs = finishedAt?.let { Duration.between(startedAt, it).toMillis().coerceAtLeast(0) },
        )
    }

    private fun Row.string(name: String): String = get(name, String::class.java).orEmpty()
    private fun Row.nullableString(name: String): String? = get(name, String::class.java)
    private fun Row.int(name: String): Int = get(name, java.lang.Integer::class.java)?.toInt() ?: 0
    private fun Row.long(name: String): Long = get(name, java.lang.Long::class.java)?.toLong() ?: 0
    private fun Row.instant(name: String): Instant = nullableInstant(name) ?: Instant.EPOCH
    private fun Row.nullableInstant(name: String): Instant? =
        get(name, Instant::class.java)
            ?: get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
}
