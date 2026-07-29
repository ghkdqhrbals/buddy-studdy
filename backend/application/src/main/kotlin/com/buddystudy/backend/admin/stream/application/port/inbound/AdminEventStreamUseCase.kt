package com.buddystudy.backend.admin.stream.application.port.inbound

import com.buddystudy.backend.admin.stream.application.model.AdminCursorPage
import com.buddystudy.backend.admin.stream.application.model.AdminPushOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminRedisEventOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamPendingEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamTopicSummary

interface AdminEventStreamUseCase {
    suspend fun topics(query: String?): List<AdminStreamTopicSummary>

    suspend fun streamEntries(
        topic: String,
        cursor: String?,
        limit: Int,
        eventType: String?,
    ): AdminCursorPage<AdminStreamEntry>

    suspend fun streamEntry(topic: String, entryId: String): AdminStreamEntry

    suspend fun pendingEntries(
        topic: String,
        group: String,
        cursor: String?,
        limit: Int,
    ): AdminCursorPage<AdminStreamPendingEntry>

    suspend fun redisEventOutbox(
        cursor: String?,
        limit: Int,
        status: String?,
        eventType: String?,
    ): AdminCursorPage<AdminRedisEventOutboxEntry>

    suspend fun pushOutbox(
        cursor: String?,
        limit: Int,
        status: String?,
    ): AdminCursorPage<AdminPushOutboxEntry>
}
