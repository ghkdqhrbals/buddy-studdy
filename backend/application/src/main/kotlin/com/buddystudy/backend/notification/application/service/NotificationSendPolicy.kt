package com.buddystudy.backend.notification.application.service

import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import org.springframework.stereotype.Component

@Component
class NotificationSendPolicy(
    private val permissions: PermissionEvaluator,
) {
    fun canSendPush(command: NotificationRequestCommand): Boolean {
        if (!command.shouldPush) return false
        val userId = command.userId ?: return false
        val deviceId = command.deviceId ?: return false
        val permissionCode = permissionFor(command.type)
        return permissions.evaluate(userId, deviceId, permissionCode).granted
    }

    private fun permissionFor(type: String): String {
        val normalized = type.uppercase()
        return when {
            "NIGHT" in normalized && "MARKETING" in normalized -> Permissions.NOTIFICATION_RECEIVE_NIGHT_MARKETING
            "MARKETING" in normalized -> Permissions.NOTIFICATION_RECEIVE_MARKETING
            else -> Permissions.NOTIFICATION_RECEIVE_INFO
        }
    }
}
