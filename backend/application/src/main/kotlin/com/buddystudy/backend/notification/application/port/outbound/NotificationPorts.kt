package com.buddystudy.backend.notification.application.port.outbound

import com.buddystudy.notification.domain.entity.AppNotificationEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant

interface NotificationPersistencePort {
    suspend fun save(entity: AppNotificationEntity): AppNotificationEntity
    suspend fun findByEventId(eventId: String): AppNotificationEntity?
    suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): AppNotificationEntity?
    suspend fun findByIdAndDeviceIdAndUserIdIsNullAndDeletedAtIsNull(id: Long, deviceId: String): AppNotificationEntity?
    suspend fun findByUserIdAndDeletedAtIsNull(userId: Long, pageable: Pageable): Page<AppNotificationEntity>
    suspend fun findVisible(userId: Long?, deviceId: String, pageable: Pageable): Page<AppNotificationEntity>
    suspend fun countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId: Long): Long
    suspend fun countVisibleUnread(userId: Long?, deviceId: String): Long
    suspend fun markAllDeleted(userId: Long, deletedAt: Instant): Int
    suspend fun markVisibleDeleted(userId: Long?, deviceId: String, deletedAt: Instant): Int
    suspend fun markUserThreadRead(userId: Long, threadType: String, threadId: String, readAt: Instant): Int
    suspend fun claimPush(id: Long, now: Instant, staleBefore: Instant): Int
    suspend fun markPushSent(id: Long, now: Instant): Int
    suspend fun markPushFailed(id: Long, error: String, now: Instant): Int
}

interface NotificationStreamPublishPort {
    suspend fun publishNotification(command: com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand): Boolean
}
