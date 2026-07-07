package com.buddystudy.backend.notification.adapter.outbound.stream

import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Value
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class NotificationRequestedEvent(
    val userId: Long? = null,
    val deviceId: String? = null,
    val actorUserId: Long? = null,
    val type: String = "ACTIVITY",
    val title: String,
    val body: String,
    val threadType: String? = null,
    val threadId: String? = null,
    val deepLink: String? = null,
    val metadataJson: String? = null,
    val shouldPush: Boolean = false,
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String = "NOTIFICATION_REQUESTED",
)

data class NotificationRequestedPayload(
    val eventId: String,
    val userId: Long? = null,
    val deviceId: String? = null,
    val actorUserId: Long? = null,
    val type: String,
    val title: String,
    val body: String,
    val threadType: String?,
    val threadId: String?,
    val deepLink: String?,
    val metadataJson: String?,
    val shouldPush: Boolean,
)

private val notificationEventMapper = jacksonObjectMapper()
    .registerModule(JavaTimeModule())
    .setDefaultPropertyInclusion(Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

fun NotificationRequestedEvent.toRedisStreamFields(): Map<String, String> =
    mapOf(
        "eventId" to eventId,
        "eventType" to eventType,
        "payload" to notificationEventMapper.writeValueAsString(toPayload()),
    )

fun NotificationRequestedEvent.toCommand(): NotificationRequestCommand =
    NotificationRequestCommand(
        eventId = eventId,
        userId = userId,
        deviceId = deviceId,
        actorUserId = actorUserId,
        type = type,
        title = title,
        body = body,
        threadType = threadType,
        threadId = threadId,
        deepLink = deepLink,
        metadataJson = metadataJson,
        shouldPush = shouldPush,
    )

private fun NotificationRequestedEvent.toPayload(): NotificationRequestedPayload =
    NotificationRequestedPayload(
        eventId = eventId,
        userId = userId,
        deviceId = deviceId,
        actorUserId = actorUserId,
        type = type,
        title = title,
        body = body,
        threadType = threadType,
        threadId = threadId,
        deepLink = deepLink,
        metadataJson = metadataJson,
        shouldPush = shouldPush,
    )
