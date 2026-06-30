package com.buddystudy.backend.notification.adapter.inbound.web

import com.buddystudy.backend.notification.application.model.AppNotificationsResponse
import com.buddystudy.backend.notification.application.model.NotificationMutationResponse
import com.buddystudy.backend.notification.application.model.NotificationUnreadCountResponse
import org.springframework.security.core.Authentication

interface NotificationWebPort {
    fun notifications(limit: Int, offset: Int, authentication: Authentication): AppNotificationsResponse
    fun unreadCount(authentication: Authentication): NotificationUnreadCountResponse
    fun markRead(id: Long, authentication: Authentication): NotificationMutationResponse
    fun delete(id: Long, authentication: Authentication): NotificationMutationResponse
    fun deleteAll(authentication: Authentication): NotificationMutationResponse
}
