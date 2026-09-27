package com.buddystudy.backend.notification.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.notification.application.model.NotificationMutationResponse
import com.buddystudy.backend.notification.application.model.NotificationOpenSource
import com.buddystudy.backend.notification.application.port.inbound.TrackNotificationOpenUseCase
import com.buddystudy.backend.notification.application.port.outbound.NotificationOpenPersistencePort
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class NotificationOpenService(
    private val notifications: NotificationPersistencePort,
    private val opens: NotificationOpenPersistencePort,
) : TrackNotificationOpenUseCase {
    @Transactional
    override suspend fun open(principal: Principal, notificationId: Long, source: NotificationOpenSource): NotificationMutationResponse {
        val owned = principal.userId.takeUnless { principal.anonymous }
            ?.let { notifications.findByIdAndUserIdAndDeletedAtIsNull(notificationId, it) }
            ?: notifications.findByIdAndDeviceIdAndUserIdIsNullAndDeletedAtIsNull(notificationId, principal.deviceId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Notification not found.")
        // Reading a row or read-all does not enter this path. Each explicit source is recorded once.
        opens.markOpen(owned.id, source, Instant.now())
        return NotificationMutationResponse()
    }
}
