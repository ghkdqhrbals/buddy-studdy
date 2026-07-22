package com.buddystudy.backend.notification.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.notification.application.model.AppNotificationsResponse
import com.buddystudy.backend.notification.application.model.AppNotificationResponse
import com.buddystudy.backend.notification.application.model.NotificationMutationResponse
import com.buddystudy.backend.notification.application.model.NotificationUnreadCountResponse
import com.buddystudy.backend.notification.application.port.inbound.BrowseNotificationsUseCase
import com.buddystudy.backend.notification.application.port.inbound.MutateNotificationsUseCase
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.ProcessNotificationEventUseCase
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.backend.notification.application.port.outbound.NotificationStreamPublishPort
import com.buddystudy.notification.domain.entity.AppNotificationEntity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class NotificationService(
    private val notificationStore: NotificationPersistencePort,
    private val publisher: NotificationStreamPublishPort,
) : BrowseNotificationsUseCase,
    MutateNotificationsUseCase,
    ProcessNotificationEventUseCase,
    PublishNotificationUseCase {

    @Transactional(readOnly = true)
    override suspend fun notifications(principal: Principal, limit: Int, offset: Int): AppNotificationsResponse {
        val page = notificationStore.findVisible(
            visibleUserId(principal),
            principal.deviceId,
            PageRequest.of(
                offset / limit,
                limit,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
            ),
        )
        return AppNotificationsResponse(
            notifications = page.content.map { toResponse(it) },
            unreadCount = notificationStore.countVisibleUnread(visibleUserId(principal), principal.deviceId),
            totalCount = page.totalElements,
            limit = limit,
            offset = offset,
        )
    }

    @Transactional(readOnly = true)
    override suspend fun unreadCount(principal: Principal): NotificationUnreadCountResponse =
        NotificationUnreadCountResponse(notificationStore.countVisibleUnread(visibleUserId(principal), principal.deviceId))

    @Transactional
    override suspend fun markRead(principal: Principal, id: Long): NotificationMutationResponse {
        val notification = owned(id, principal)
        if (notification.readAt == null) {
            val now = Instant.now()
            notification.readAt = now
            notification.updatedAt = now
            notificationStore.save(notification)
            if (notification.userId != null && notification.threadType != null && notification.threadId != null) {
                notificationStore.markUserThreadRead(notification.userId!!, notification.threadType!!, notification.threadId!!, now)
            }
        }
        return NotificationMutationResponse()
    }

    @Transactional
    override suspend fun delete(principal: Principal, id: Long): NotificationMutationResponse {
        val notification = owned(id, principal)
        if (notification.deletedAt == null) {
            val now = Instant.now()
            notification.deletedAt = now
            notification.updatedAt = now
            notificationStore.save(notification)
        }
        return NotificationMutationResponse()
    }

    @Transactional
    override suspend fun deleteAll(principal: Principal): NotificationMutationResponse {
        notificationStore.markVisibleDeleted(visibleUserId(principal), principal.deviceId, Instant.now())
        return NotificationMutationResponse()
    }

    @Transactional
    override suspend fun process(command: NotificationRequestCommand): Long {
        require(command.userId != null || !command.deviceId.isNullOrBlank()) {
            "Notification owner is required."
        }
        notificationStore.findByEventId(command.eventId)?.let {
            return it.id
        }
        val now = Instant.now()
        try {
            return notificationStore.save(
                AppNotificationEntity(
                    eventId = command.eventId,
                    userId = command.userId,
                    deviceId = command.deviceId,
                    actorUserId = command.actorUserId,
                    type = command.type,
                    title = command.title.take(160),
                    body = command.body,
                    threadType = command.threadType,
                    threadId = command.threadId,
                    deepLink = command.deepLink,
                    metadataJson = command.metadataJson,
                    shouldPush = command.shouldPush,
                    createdAt = now,
                    updatedAt = now,
                )
            ).id
        } catch (duplicate: DataIntegrityViolationException) {
            return notificationStore.findByEventId(command.eventId)?.id ?: throw duplicate
        }
    }

    override suspend fun publish(command: NotificationRequestCommand): Boolean =
        publisher.publishNotification(command)

    private suspend fun owned(id: Long, principal: Principal): AppNotificationEntity =
        visibleUserId(principal)
            ?.let { notificationStore.findByIdAndUserIdAndDeletedAtIsNull(id, it) }
            ?: notificationStore.findByIdAndDeviceIdAndUserIdIsNullAndDeletedAtIsNull(id, principal.deviceId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Notification not found.")

    private suspend fun visibleUserId(principal: Principal): Long? =
        principal.userId.takeUnless { principal.anonymous }

    private suspend fun toResponse(notification: AppNotificationEntity): AppNotificationResponse =
        AppNotificationResponse(
            id = notification.id.toString(),
            type = notification.type,
            title = notification.title,
            body = notification.body,
            threadType = notification.threadType,
            threadId = notification.threadId,
            deepLink = notification.deepLink,
            isRead = notification.readAt != null,
            createdAt = notification.createdAt,
            readAt = notification.readAt,
        )
}
