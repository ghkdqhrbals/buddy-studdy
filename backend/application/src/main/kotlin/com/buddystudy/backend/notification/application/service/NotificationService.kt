package com.buddystudy.backend.notification.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.notification.application.model.AppNotificationsResponse
import com.buddystudy.backend.notification.application.model.NotificationMutationResponse
import com.buddystudy.backend.notification.application.model.NotificationUnreadCountResponse
import com.buddystudy.backend.notification.application.model.toResponse
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
    override fun notifications(principal: Principal, limit: Int, offset: Int): AppNotificationsResponse {
        val page = notificationStore.findByUserIdAndDeletedAtIsNull(
            principal.userId,
            PageRequest.of(
                offset / limit,
                limit,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
            ),
        )
        return AppNotificationsResponse(
            notifications = page.content.map { it.toResponse() },
            unreadCount = notificationStore.countByUserIdAndReadAtIsNullAndDeletedAtIsNull(principal.userId),
            totalCount = page.totalElements,
            limit = limit,
            offset = offset,
        )
    }

    @Transactional(readOnly = true)
    override fun unreadCount(principal: Principal): NotificationUnreadCountResponse =
        NotificationUnreadCountResponse(notificationStore.countByUserIdAndReadAtIsNullAndDeletedAtIsNull(principal.userId))

    @Transactional
    override fun markRead(principal: Principal, id: Long): NotificationMutationResponse {
        val notification = owned(id, principal.userId)
        if (notification.readAt == null) {
            val now = Instant.now()
            notification.readAt = now
            notification.updatedAt = now
            notificationStore.save(notification)
        }
        return NotificationMutationResponse()
    }

    @Transactional
    override fun delete(principal: Principal, id: Long): NotificationMutationResponse {
        val notification = owned(id, principal.userId)
        if (notification.deletedAt == null) {
            val now = Instant.now()
            notification.deletedAt = now
            notification.updatedAt = now
            notificationStore.save(notification)
        }
        return NotificationMutationResponse()
    }

    @Transactional
    override fun deleteAll(principal: Principal): NotificationMutationResponse {
        notificationStore.markAllDeleted(principal.userId, Instant.now())
        return NotificationMutationResponse()
    }

    @Transactional
    override fun process(command: NotificationRequestCommand): Long {
        notificationStore.findByEventId(command.eventId)?.let {
            return it.id
        }
        val now = Instant.now()
        try {
            return notificationStore.save(
                AppNotificationEntity(
                    eventId = command.eventId,
                    userId = command.userId,
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

    override fun publish(command: NotificationRequestCommand): Boolean =
        publisher.publishNotification(command)

    private fun owned(id: Long, userId: Long): AppNotificationEntity =
        notificationStore.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Notification not found.")
}
