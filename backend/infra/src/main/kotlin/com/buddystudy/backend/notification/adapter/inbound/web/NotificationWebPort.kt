package com.buddystudy.backend.notification.adapter.inbound.web

import com.buddystudy.backend.notification.application.model.AppNotificationsResponse
import com.buddystudy.backend.notification.application.model.NotificationMutationResponse
import com.buddystudy.backend.notification.application.model.NotificationUnreadCountResponse
import org.springframework.security.core.Authentication

interface NotificationWebPort {
    suspend fun notifications(limit: Int, offset: Int, authentication: Authentication): AppNotificationsResponse
    suspend fun unreadCount(authentication: Authentication): NotificationUnreadCountResponse
    suspend fun markRead(id: Long, authentication: Authentication): NotificationMutationResponse
    suspend fun markAllRead(authentication: Authentication): NotificationMutationResponse
    suspend fun delete(id: Long, authentication: Authentication): NotificationMutationResponse
    suspend fun deleteAll(authentication: Authentication): NotificationMutationResponse
}
