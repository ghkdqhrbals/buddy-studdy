package com.buddystudy.backend.admin.management.application.service

import com.buddystudy.backend.admin.management.application.model.AdminNotificationCommand
import com.buddystudy.backend.admin.management.application.model.AdminNotificationDispatchResponse
import com.buddystudy.backend.admin.management.application.port.inbound.AdminMessagingUseCase
import com.buddystudy.backend.admin.management.application.port.outbound.AdminFeedbackPort
import com.buddystudy.backend.admin.management.application.port.outbound.AdminManagementPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Instant
import java.util.UUID

@Service
class AdminMessagingService(
    private val management: AdminManagementPort,
    private val feedbackStore: AdminFeedbackPort,
    private val notifications: PublishNotificationUseCase,
) : AdminMessagingUseCase {
    @Transactional
    override suspend fun notifyUser(
        userId: Long,
        command: AdminNotificationCommand,
    ): AdminNotificationDispatchResponse {
        management.user(userId)
            ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RESOURCE_NOT_FOUND,
                "User not found.",
            )
        return publish(command, targetUserId = userId, targetDeviceId = null, feedbackId = null)
    }

    @Transactional
    override suspend fun notifyFeedback(
        feedbackId: Long,
        command: AdminNotificationCommand,
    ): AdminNotificationDispatchResponse {
        val feedback = feedbackStore.feedback(feedbackId)
            ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RESOURCE_NOT_FOUND,
                "Feedback not found.",
            )
        if (feedback.userId == null && feedback.deviceId.isNullOrBlank()) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Feedback has no notification target.",
            )
        }
        val result = publish(
            command = command,
            targetUserId = feedback.userId,
            targetDeviceId = feedback.userId?.let { null } ?: feedback.deviceId,
            feedbackId = feedbackId,
        )
        feedbackStore.markReplied(feedbackId, Instant.now())
        return result
    }

    private suspend fun publish(
        command: AdminNotificationCommand,
        targetUserId: Long?,
        targetDeviceId: String?,
        feedbackId: Long?,
    ): AdminNotificationDispatchResponse {
        val title = command.title.trim()
        if (title.isEmpty() || title.length > 160) {
            invalid("Notification title must contain between 1 and 160 characters.")
        }
        if (command.body.isBlank() || command.body.length > 2_000) {
            invalid("Notification body must contain between 1 and 2000 characters.")
        }
        val deepLink = validatedDeepLink(command.deepLink)
        val eventId = "admin-message-${UUID.randomUUID()}"
        val published = notifications.publish(
            NotificationRequestCommand(
                eventId = eventId,
                userId = targetUserId,
                deviceId = targetDeviceId,
                type = "ADMIN_MESSAGE",
                title = title,
                body = command.body,
                threadType = "admin_message",
                threadId = feedbackId?.toString(),
                deepLink = deepLink,
                metadataJson = feedbackId?.let { """{"feedbackId":$it}""" },
                shouldPush = true,
            ),
        )
        if (!published) {
            throw ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.SERVER_BUSY,
                "Notification could not be queued.",
            )
        }
        return AdminNotificationDispatchResponse(
            eventId = eventId,
            targetUserId = targetUserId,
            targetDeviceId = targetDeviceId,
            deepLink = deepLink,
            feedbackId = feedbackId,
        )
    }

    private fun validatedDeepLink(raw: String?): String {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_DEEP_LINK
        if (value.length > 1_000) invalid("Notification deep link is too long.")
        val uri = runCatching { URI(value) }.getOrNull()
            ?: invalid("Notification deep link is invalid.")
        if (uri.scheme?.lowercase() != "buddystudy") {
            invalid("Only BuddyStudy app deep links are allowed.")
        }
        val destination = uri.host?.lowercase()?.takeIf(String::isNotBlank)
            ?: uri.path.orEmpty().trim('/').substringBefore('/').lowercase()
        if (destination !in ALLOWED_DESTINATIONS) {
            invalid("Notification deep link destination is not supported.")
        }
        return value
    }

    private fun invalid(message: String): Nothing =
        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, message)

    private companion object {
        const val DEFAULT_DEEP_LINK = "buddystudy://home/message"
        val ALLOWED_DESTINATIONS = setOf(
            "home",
            "study",
            "studies",
            "record",
            "records",
            "history",
            "stats",
            "statistics",
            "settings",
            "profile",
            "public",
        )
    }
}
