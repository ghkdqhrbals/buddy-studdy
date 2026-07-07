package com.buddystudy.backend.notification.application.port.outbound

import com.buddystudy.notification.domain.entity.AppNotificationEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant

interface NotificationPersistencePort {
    fun save(entity: AppNotificationEntity): AppNotificationEntity
    fun findByEventId(eventId: String): AppNotificationEntity?
    fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): AppNotificationEntity?
    fun findByIdAndDeviceIdAndUserIdIsNullAndDeletedAtIsNull(id: Long, deviceId: String): AppNotificationEntity?
    fun findByUserIdAndDeletedAtIsNull(userId: Long, pageable: Pageable): Page<AppNotificationEntity>
    fun findVisible(userId: Long?, deviceId: String, pageable: Pageable): Page<AppNotificationEntity>
    fun countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId: Long): Long
    fun countVisibleUnread(userId: Long?, deviceId: String): Long
    fun markAllDeleted(userId: Long, deletedAt: Instant): Int
    fun markVisibleDeleted(userId: Long?, deviceId: String, deletedAt: Instant): Int
    fun markUserThreadRead(userId: Long, threadType: String, threadId: String, readAt: Instant): Int
    fun claimPush(id: Long, now: Instant, staleBefore: Instant): Int
    fun markPushSent(id: Long, now: Instant): Int
    fun markPushFailed(id: Long, error: String, now: Instant): Int
}

interface NotificationStreamPublishPort {
    fun publishNotification(command: com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand): Boolean
}
