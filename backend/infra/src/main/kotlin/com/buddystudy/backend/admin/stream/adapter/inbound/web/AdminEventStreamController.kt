package com.buddystudy.backend.admin.stream.adapter.inbound.web

import com.buddystudy.backend.admin.analytics.application.port.inbound.AdminAnalyticsUseCase
import com.buddystudy.backend.admin.stream.application.model.AdminCursorPage
import com.buddystudy.backend.admin.stream.application.model.AdminPushOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminRedisEventOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamTopicSummary
import com.buddystudy.backend.admin.stream.application.port.inbound.AdminEventStreamUseCase
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/event-streams")
class AdminEventStreamController(
    private val streams: AdminEventStreamWebPort,
) {
    @GetMapping("/topics")
    suspend fun topics(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(required = false) query: String?,
    ): List<AdminStreamTopicSummary> =
        streams.topics(authorization.adminBearerToken(), query)

    @GetMapping("/topics/{topic}/entries")
    suspend fun entries(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable topic: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) eventType: String?,
    ): AdminCursorPage<AdminStreamEntry> =
        streams.entries(authorization.adminBearerToken(), topic, cursor, limit, eventType)

    @GetMapping("/topics/{topic}/entries/{entryId}")
    suspend fun entry(
        @RequestHeader("Authorization") authorization: String?,
        @PathVariable topic: String,
        @PathVariable entryId: String,
    ): AdminStreamEntry =
        streams.entry(authorization.adminBearerToken(), topic, entryId)

    @GetMapping("/outboxes/events")
    suspend fun eventOutbox(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) eventType: String?,
    ): AdminCursorPage<AdminRedisEventOutboxEntry> =
        streams.eventOutbox(authorization.adminBearerToken(), cursor, limit, status, eventType)

    @GetMapping("/outboxes/pushes")
    suspend fun pushOutbox(
        @RequestHeader("Authorization") authorization: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) status: String?,
    ): AdminCursorPage<AdminPushOutboxEntry> =
        streams.pushOutbox(authorization.adminBearerToken(), cursor, limit, status)
}

interface AdminEventStreamWebPort {
    suspend fun topics(adminToken: String, query: String?): List<AdminStreamTopicSummary>

    suspend fun entries(
        adminToken: String,
        topic: String,
        cursor: String?,
        limit: Int,
        eventType: String?,
    ): AdminCursorPage<AdminStreamEntry>

    suspend fun entry(adminToken: String, topic: String, entryId: String): AdminStreamEntry

    suspend fun eventOutbox(
        adminToken: String,
        cursor: String?,
        limit: Int,
        status: String?,
        eventType: String?,
    ): AdminCursorPage<AdminRedisEventOutboxEntry>

    suspend fun pushOutbox(
        adminToken: String,
        cursor: String?,
        limit: Int,
        status: String?,
    ): AdminCursorPage<AdminPushOutboxEntry>
}

@Component
class AdminEventStreamWebAdapter(
    private val authentication: AdminAnalyticsUseCase,
    private val streams: AdminEventStreamUseCase,
) : AdminEventStreamWebPort {
    override suspend fun topics(adminToken: String, query: String?): List<AdminStreamTopicSummary> {
        authentication.validate(adminToken)
        return streams.topics(query)
    }

    override suspend fun entries(
        adminToken: String,
        topic: String,
        cursor: String?,
        limit: Int,
        eventType: String?,
    ): AdminCursorPage<AdminStreamEntry> {
        authentication.validate(adminToken)
        return streams.streamEntries(topic, cursor, limit, eventType)
    }

    override suspend fun entry(adminToken: String, topic: String, entryId: String): AdminStreamEntry {
        authentication.validate(adminToken)
        return streams.streamEntry(topic, entryId)
    }

    override suspend fun eventOutbox(
        adminToken: String,
        cursor: String?,
        limit: Int,
        status: String?,
        eventType: String?,
    ): AdminCursorPage<AdminRedisEventOutboxEntry> {
        authentication.validate(adminToken)
        return streams.redisEventOutbox(cursor, limit, status, eventType)
    }

    override suspend fun pushOutbox(
        adminToken: String,
        cursor: String?,
        limit: Int,
        status: String?,
    ): AdminCursorPage<AdminPushOutboxEntry> {
        authentication.validate(adminToken)
        return streams.pushOutbox(cursor, limit, status)
    }
}

private fun String?.adminBearerToken(): String =
    this?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
