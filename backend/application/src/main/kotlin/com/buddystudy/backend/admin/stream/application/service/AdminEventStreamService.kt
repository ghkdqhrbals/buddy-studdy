package com.buddystudy.backend.admin.stream.application.service

import com.buddystudy.backend.admin.stream.application.model.AdminCursorPage
import com.buddystudy.backend.admin.stream.application.model.AdminPushOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminRedisEventOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamTopicSummary
import com.buddystudy.backend.admin.stream.application.port.inbound.AdminEventStreamUseCase
import com.buddystudy.backend.admin.stream.application.port.outbound.AdminOutboxInspectionPort
import com.buddystudy.backend.admin.stream.application.port.outbound.AdminRedisStreamInspectionPort
import org.springframework.stereotype.Service

@Service
class AdminEventStreamService(
    private val streams: AdminRedisStreamInspectionPort,
    private val outboxes: AdminOutboxInspectionPort,
) : AdminEventStreamUseCase {
    override suspend fun topics(): List<AdminStreamTopicSummary> = streams.topics()

    override suspend fun streamEntries(
        topic: String,
        cursor: String?,
        limit: Int,
        eventType: String?,
    ): AdminCursorPage<AdminStreamEntry> =
        streams.entries(topic, cursor.validStreamCursor(), limit.normalized(), eventType.normalized())

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
