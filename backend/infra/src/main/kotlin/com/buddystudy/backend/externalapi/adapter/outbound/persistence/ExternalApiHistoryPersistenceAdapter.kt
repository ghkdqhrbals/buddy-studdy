package com.buddystudy.backend.externalapi.adapter.outbound.persistence

import com.buddystudy.backend.externalapi.application.model.ExternalApiCallHistory
import com.buddystudy.backend.externalapi.application.model.ExternalApiCallHistorySummary
import com.buddystudy.backend.externalapi.application.model.ExternalApiHistoryPage
import com.buddystudy.backend.externalapi.application.model.ExternalApiHistoryQuery
import com.buddystudy.backend.externalapi.application.model.FinishExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.model.StartExternalApiCallCommand
import com.buddystudy.backend.externalapi.application.port.outbound.ExternalApiHistoryPort
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class ExternalApiHistoryPersistenceAdapter(
    private val database: DatabaseClient,
) : ExternalApiHistoryPort {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun start(command: StartExternalApiCallCommand) {
        var statement = database.sql(
            """
            insert into external_api_call_history (
                call_id, correlation_id, provider, operation, http_method, request_url,
                request_headers_json, request_body, status, started_at, created_at, updated_at
            ) values (
                :callId, :correlationId, :provider, :operation, :httpMethod, :requestUrl,
                :requestHeadersJson, :requestBody, 'STARTED', :startedAt, :startedAt, :startedAt
            )
            """.trimIndent(),
        )
            .bind("callId", command.callId)
            .bind("provider", command.provider)
            .bind("operation", command.operation)
            .bind("httpMethod", command.httpMethod)
            .bind("requestUrl", command.requestUrl)
            .bind("requestHeadersJson", command.requestHeadersJson)
            .bind("startedAt", command.startedAt.utcDateTime())
        statement = statement.bindNullable("correlationId", command.correlationId, String::class.java)
        statement = statement.bindNullable("requestBody", command.requestBody, String::class.java)
        statement.fetch().rowsUpdated().awaitSingle()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override suspend fun finish(command: FinishExternalApiCallCommand): Boolean {
        var statement = database.sql(
            """
            update external_api_call_history
            set response_status = :responseStatus,
                response_headers_json = :responseHeadersJson,
                response_body = :responseBody,
                status = :status,
                error_type = :errorType,
                error_message = :errorMessage,
                finished_at = :finishedAt,
                duration_ms = timestampdiff(microsecond, started_at, :finishedAt) div 1000,
                updated_at = :finishedAt
            where call_id = :callId
              and status = 'STARTED'
            """.trimIndent(),
        )
            .bind("status", command.status)
            .bind("finishedAt", command.finishedAt.utcDateTime())
            .bind("callId", command.callId)
        statement = statement.bindNullable("responseStatus", command.responseStatus, Int::class.javaObjectType)
        statement = statement.bindNullable("responseHeadersJson", command.responseHeadersJson, String::class.java)
        statement = statement.bindNullable("responseBody", command.responseBody, String::class.java)
        statement = statement.bindNullable("errorType", command.errorType, String::class.java)
        statement = statement.bindNullable("errorMessage", command.errorMessage, String::class.java)
        return statement.fetch().rowsUpdated().awaitSingle() > 0
    }

    override suspend fun page(query: ExternalApiHistoryQuery): ExternalApiHistoryPage {
        val where = buildList {
            query.cursor?.let { add("id < :cursor") }
            query.provider?.let { add("provider = :provider") }
            query.status?.let { add("status = :status") }
            query.query?.let {
                add(
                    """
                    (
                        call_id like :query
                        or coalesce(correlation_id, '') like :query
                        or operation like :query
                        or request_url like :query
                        or coalesce(error_type, '') like :query
                        or coalesce(error_message, '') like :query
                    )
                    """.trimIndent(),
                )
            }
        }.joinToString(
            prefix = if (query.cursor != null || query.provider != null || query.status != null || query.query != null) "where " else "",
            separator = " and ",
        )
        var statement = database.sql(
            """
            select $SUMMARY_COLUMNS
            from external_api_call_history
            $where
            order by id desc
            limit :fetchLimit
            """.trimIndent(),
        ).bind("fetchLimit", query.limit + 1)
        query.cursor?.let { statement = statement.bind("cursor", it) }
        query.provider?.let { statement = statement.bind("provider", it) }
        query.status?.let { statement = statement.bind("status", it) }
        query.query?.let { statement = statement.bind("query", "%$it%") }
        val rows = statement.map { row, _ -> row.toSummary() }.all().collectList().awaitSingle()
        val items = rows.take(query.limit)
        return ExternalApiHistoryPage(
            items = items,
            nextCursor = items.lastOrNull()?.id?.toString()?.takeIf { rows.size > query.limit },
            hasMore = rows.size > query.limit,
            limit = query.limit,
        )
    }

    override suspend fun find(id: Long): ExternalApiCallHistory? = database.sql(
        "select $COLUMNS from external_api_call_history where id = :id",
    )
        .bind("id", id)
        .map { row, _ -> row.toHistory() }
        .one()
        .awaitSingleOrNull()

    private fun Row.toHistory(): ExternalApiCallHistory {
        val startedAt = instant("started_at")
        val finishedAt = nullableInstant("finished_at")
        return ExternalApiCallHistory(
            id = long("id"),
            callId = string("call_id"),
            correlationId = nullableString("correlation_id"),
            provider = string("provider"),
            operation = string("operation"),
            httpMethod = string("http_method"),
            requestUrl = string("request_url"),
            requestHeadersJson = string("request_headers_json"),
            requestBody = nullableString("request_body"),
            responseStatus = nullableInt("response_status"),
            responseHeadersJson = nullableString("response_headers_json"),
            responseBody = nullableString("response_body"),
            status = string("status"),
            errorType = nullableString("error_type"),
            errorMessage = nullableString("error_message"),
            startedAt = startedAt,
            finishedAt = finishedAt,
            durationMs = get("duration_ms", java.lang.Long::class.java)?.toLong()
                ?: finishedAt?.let { Duration.between(startedAt, it).toMillis().coerceAtLeast(0) },
        )
    }

    private fun Row.toSummary(): ExternalApiCallHistorySummary {
        val startedAt = instant("started_at")
        val finishedAt = nullableInstant("finished_at")
        return ExternalApiCallHistorySummary(
            id = long("id"),
            callId = string("call_id"),
            correlationId = nullableString("correlation_id"),
            provider = string("provider"),
            operation = string("operation"),
            httpMethod = string("http_method"),
            requestUrl = string("request_url"),
            responseStatus = nullableInt("response_status"),
            status = string("status"),
            errorType = nullableString("error_type"),
            errorMessage = nullableString("error_message"),
            startedAt = startedAt,
            finishedAt = finishedAt,
            durationMs = get("duration_ms", java.lang.Long::class.java)?.toLong()
                ?: finishedAt?.let { Duration.between(startedAt, it).toMillis().coerceAtLeast(0) },
        )
    }

    private fun Row.string(name: String): String = get(name, String::class.java).orEmpty()
    private fun Row.nullableString(name: String): String? = get(name, String::class.java)
    private fun Row.nullableInt(name: String): Int? = get(name, Integer::class.java)?.toInt()
    private fun Row.long(name: String): Long = get(name, java.lang.Long::class.java)?.toLong() ?: 0
    private fun Row.instant(name: String): Instant = nullableInstant(name) ?: Instant.EPOCH
    private fun Row.nullableInstant(name: String): Instant? =
        get(name, Instant::class.java) ?: get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    private companion object {
        val SUMMARY_COLUMNS = """
            id, call_id, correlation_id, provider, operation, http_method, request_url,
            response_status, status, error_type, error_message, started_at, finished_at, duration_ms
        """.trimIndent()
        val COLUMNS = """
            id, call_id, correlation_id, provider, operation, http_method, request_url,
            request_headers_json, request_body, response_status, response_headers_json, response_body,
            status, error_type, error_message, started_at, finished_at, duration_ms
        """.trimIndent()
    }
}

private fun Instant.utcDateTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?,
    type: Class<T>,
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)
