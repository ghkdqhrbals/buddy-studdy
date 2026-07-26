package com.buddystudy.backend.admin.stream.application.port.outbound

import com.buddystudy.backend.admin.stream.application.model.AdminCursorPage
import com.buddystudy.backend.admin.stream.application.model.AdminPushOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminRedisEventOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamEntry
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
}

interface AdminOutboxInspectionPort {
    suspend fun redisEvents(
        cursor: Long?,
        limit: Int,
        status: String?,
        eventType: String?,
    ): AdminCursorPage<AdminRedisEventOutboxEntry>

    suspend fun pushes(
        cursor: Long?,
        limit: Int,
        status: String?,
    ): AdminCursorPage<AdminPushOutboxEntry>
}
