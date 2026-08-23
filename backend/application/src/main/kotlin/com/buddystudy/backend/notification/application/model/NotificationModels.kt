package com.buddystudy.backend.notification.application.model

import com.buddystudy.backend.common.application.model.PageResponse
import com.buddystudy.notification.domain.entity.AppNotificationEntity
import java.time.Instant

data class AppNotificationResponse(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val threadType: String?,
    val threadId: String?,
    val deepLink: String?,
    val isRead: Boolean,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class AppNotificationsResponse(
    val notifications: List<AppNotificationResponse>,
    val unreadCount: Long,
    override val totalCount: Long,
    override val limit: Int,
    override val offset: Int,
) : PageResponse

data class NotificationUnreadCountResponse(val unreadCount: Long)

data class NotificationMutationResponse(val ok: Boolean = true)

fun AppNotificationEntity.toResponse(): AppNotificationResponse =
    AppNotificationResponse(
        id = id.toString(),
        type = type.name,
        title = title,
        body = body,
        threadType = threadType?.databaseValue,
        threadId = threadId,
        deepLink = deepLink,
        isRead = readAt != null,
        createdAt = createdAt,
        readAt = readAt,
    )
