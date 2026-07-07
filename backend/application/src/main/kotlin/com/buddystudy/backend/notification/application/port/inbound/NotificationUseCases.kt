package com.buddystudy.backend.notification.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.notification.application.model.AppNotificationsResponse
import com.buddystudy.backend.notification.application.model.NotificationMutationResponse
import com.buddystudy.backend.notification.application.model.NotificationUnreadCountResponse

data class NotificationRequestCommand(
    val eventId: String,
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
)

interface BrowseNotificationsUseCase {
    fun notifications(principal: Principal, limit: Int, offset: Int): AppNotificationsResponse
    fun unreadCount(principal: Principal): NotificationUnreadCountResponse
}

interface MutateNotificationsUseCase {
    fun markRead(principal: Principal, id: Long): NotificationMutationResponse
    fun delete(principal: Principal, id: Long): NotificationMutationResponse
    fun deleteAll(principal: Principal): NotificationMutationResponse
}

interface ProcessNotificationEventUseCase {
    fun process(command: NotificationRequestCommand): Long
}

interface PublishNotificationUseCase {
    fun publish(command: NotificationRequestCommand): Boolean
}
