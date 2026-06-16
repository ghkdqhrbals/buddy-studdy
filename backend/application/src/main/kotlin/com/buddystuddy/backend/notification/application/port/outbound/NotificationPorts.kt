package com.buddystuddy.backend.notification.application.port.outbound

import com.buddystuddy.notification.domain.entity.AppNotificationEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant

interface NotificationPersistencePort {
    fun save(entity: AppNotificationEntity): AppNotificationEntity
    fun findByEventId(eventId: String): AppNotificationEntity?
    fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): AppNotificationEntity?
    fun findByUserIdAndDeletedAtIsNull(userId: Long, pageable: Pageable): Page<AppNotificationEntity>
    fun countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId: Long): Long
    fun markAllDeleted(userId: Long, deletedAt: Instant): Int
    fun claimPush(id: Long, now: Instant, staleBefore: Instant): Int
    fun markPushSent(id: Long, now: Instant): Int
    fun markPushFailed(id: Long, error: String, now: Instant): Int
}

interface NotificationStreamPublishPort {
    fun publishNotification(command: com.buddystuddy.backend.notification.application.port.inbound.NotificationRequestCommand): Boolean
}
