package com.buddystuddy.backend.notification.adapter.inbound.web

import com.buddystuddy.backend.notification.application.model.AppNotificationsResponse
import com.buddystuddy.backend.notification.application.model.NotificationMutationResponse
import com.buddystuddy.backend.notification.application.model.NotificationUnreadCountResponse
import org.springframework.security.core.Authentication

interface NotificationWebPort {
    fun notifications(limit: Int, offset: Int, authentication: Authentication): AppNotificationsResponse
    fun unreadCount(authentication: Authentication): NotificationUnreadCountResponse
    fun markRead(id: Long, authentication: Authentication): NotificationMutationResponse
    fun delete(id: Long, authentication: Authentication): NotificationMutationResponse
    fun deleteAll(authentication: Authentication): NotificationMutationResponse
}
