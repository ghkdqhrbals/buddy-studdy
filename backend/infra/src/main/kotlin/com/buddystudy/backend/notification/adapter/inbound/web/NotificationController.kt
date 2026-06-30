package com.buddystudy.backend.notification.adapter.inbound.web

import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.notification.application.model.AppNotificationsResponse
import com.buddystudy.backend.notification.application.model.NotificationMutationResponse
import com.buddystudy.backend.notification.application.model.NotificationUnreadCountResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Authenticated in-app notification inbox APIs. Push delivery is optional and separate from these persisted notifications.")
@RequirePermission(Permissions.NOTIFICATION_READ)
class NotificationController(
    private val notifications: NotificationWebPort,
) {
    @Operation(summary = "List my notifications", description = "Returns the authenticated user's notification inbox with offset pagination and unread count.")
    @GetMapping
    fun notifications(
        @Parameter(description = "Maximum notifications to return. Server clamps this to 1..100.", example = "30")
        @RequestParam(defaultValue = "30") limit: Int,
        @Parameter(description = "Zero-based pagination offset.", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
        authentication: Authentication,
    ): AppNotificationsResponse =
        notifications.notifications(limit, offset, authentication)

    @Operation(summary = "Fetch unread notification count")
    @GetMapping("/unread-count")
    fun unreadCount(authentication: Authentication): NotificationUnreadCountResponse =
        notifications.unreadCount(authentication)

    @Operation(summary = "Mark one notification as read", description = "Used when a user taps a notification row or lands from a push.")
    @PostMapping("/{id}/read")
    fun markRead(@PathVariable id: Long, authentication: Authentication): NotificationMutationResponse =
        notifications.markRead(id, authentication)

    @Operation(summary = "Delete one notification")
    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.NOTIFICATION_DELETE)
    fun delete(@PathVariable id: Long, authentication: Authentication): NotificationMutationResponse =
        notifications.delete(id, authentication)

    @Operation(summary = "Delete all my notifications")
    @DeleteMapping
    @RequirePermission(Permissions.NOTIFICATION_DELETE)
    fun deleteAll(authentication: Authentication): NotificationMutationResponse =
        notifications.deleteAll(authentication)
}
