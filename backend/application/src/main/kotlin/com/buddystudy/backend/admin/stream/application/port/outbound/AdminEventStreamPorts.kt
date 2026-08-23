package com.buddystudy.backend.admin.stream.application.port.outbound

import com.buddystudy.backend.admin.stream.application.model.AdminCursorPage
import com.buddystudy.backend.admin.stream.application.model.AdminRedisEventOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamInboxAttempt
import com.buddystudy.backend.admin.stream.application.model.AdminStreamPendingEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamTopicSummary

interface AdminRedisStreamInspectionPort {
    suspend fun topics(): List<AdminStreamTopicSummary>

    suspend fun entries(
        topic: String,
        cursor: String?,
        limit: Int,
        eventType: String?,
    ): AdminCursorPage<AdminStreamEntry>

    suspend fun entry(topic: String, entryId: String): AdminStreamEntry?

    suspend fun pending(
        topic: String,
        group: String,
        cursor: String?,
        limit: Int,
    ): AdminCursorPage<AdminStreamPendingEntry>
}

interface AdminOutboxInspectionPort {
    suspend fun redisEvents(
        cursor: Long?,
        limit: Int,
        status: String?,
        eventType: String?,
    ): AdminCursorPage<AdminRedisEventOutboxEntry>

}

interface AdminStreamInboxInspectionPort {
    suspend fun attempts(
        cursor: Long?,
        limit: Int,
        consumerGroup: String?,
        status: String?,
        query: String?,
    ): AdminCursorPage<AdminStreamInboxAttempt>
}
