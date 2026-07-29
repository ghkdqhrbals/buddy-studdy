package com.buddystudy.backend.admin.stream.application.service

import com.buddystudy.backend.admin.stream.application.model.AdminCursorPage
import com.buddystudy.backend.admin.stream.application.model.AdminPushOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminRedisEventOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamPendingEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamTopicSummary
import com.buddystudy.backend.admin.stream.application.port.inbound.AdminEventStreamUseCase
import com.buddystudy.backend.admin.stream.application.port.outbound.AdminOutboxInspectionPort
import com.buddystudy.backend.admin.stream.application.port.outbound.AdminRedisStreamInspectionPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class AdminEventStreamService(
    private val streams: AdminRedisStreamInspectionPort,
    private val outboxes: AdminOutboxInspectionPort,
) : AdminEventStreamUseCase {
    override suspend fun topics(query: String?): List<AdminStreamTopicSummary> {
        val normalizedQuery = query.normalized()?.lowercase()
        return streams.topics().filter { topic ->
            normalizedQuery == null ||
                topic.topic.lowercase().contains(normalizedQuery) ||
                topic.streamKey.lowercase().contains(normalizedQuery) ||
                topic.groups.any { group ->
                    group.name.lowercase().contains(normalizedQuery) ||
                        group.consumerDetails.any { consumer ->
                            consumer.name.lowercase().contains(normalizedQuery)
                        }
                }
        }
    }

    override suspend fun streamEntries(
        topic: String,
        cursor: String?,
        limit: Int,
        eventType: String?,
    ): AdminCursorPage<AdminStreamEntry> =
        streams.entries(topic, cursor.validStreamCursor(), limit.normalized(), eventType.normalized())

    override suspend fun streamEntry(topic: String, entryId: String): AdminStreamEntry {
        val normalizedEntryId = entryId.normalized()
            ?.takeIf(STREAM_CURSOR::matches)
            ?: throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Redis Stream entry ID must use the '<milliseconds>-<sequence>' format.",
            )
        return streams.entry(topic, normalizedEntryId)
            ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RESOURCE_NOT_FOUND,
                "Redis Stream entry was not found.",
            )
    }

    override suspend fun pendingEntries(
        topic: String,
        group: String,
        cursor: String?,
        limit: Int,
    ): AdminCursorPage<AdminStreamPendingEntry> {
        val normalizedGroup = group.normalized()
            ?: throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Redis Stream consumer group is required.",
            )
        return streams.pending(topic, normalizedGroup, cursor.validStreamCursor(), limit.normalized())
    }

    override suspend fun redisEventOutbox(
        cursor: String?,
        limit: Int,
        status: String?,
        eventType: String?,
    ): AdminCursorPage<AdminRedisEventOutboxEntry> =
        outboxes.redisEvents(cursor.longCursor(), limit.normalized(), status.normalized(), eventType.normalized())

    override suspend fun pushOutbox(
        cursor: String?,
        limit: Int,
        status: String?,
    ): AdminCursorPage<AdminPushOutboxEntry> =
        outboxes.pushes(cursor.longCursor(), limit.normalized(), status.normalized())

    private fun Int.normalized(): Int = coerceIn(1, 100)

    private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun String?.validStreamCursor(): String? =
        normalized()?.takeIf { STREAM_CURSOR.matches(it) }

    private fun String?.longCursor(): Long? = normalized()?.toLongOrNull()?.takeIf { it > 0 }

    private companion object {
        val STREAM_CURSOR = Regex("""\d+-\d+""")
    }
}
