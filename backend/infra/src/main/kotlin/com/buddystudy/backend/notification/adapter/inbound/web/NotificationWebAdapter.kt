package com.buddystudy.backend.notification.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.notification.application.port.inbound.BrowseNotificationsUseCase
import com.buddystudy.backend.notification.application.port.inbound.MutateNotificationsUseCase
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import kotlin.math.max
import kotlin.math.min

@Component
class NotificationWebAdapter(
    private val browse: BrowseNotificationsUseCase,
    private val mutate: MutateNotificationsUseCase,
) : NotificationWebPort {
    override suspend fun notifications(limit: Int, offset: Int, authentication: Authentication) =
        browse.notifications(authentication.principalOrThrow(), min(max(1, limit), 100), max(0, offset))

    override suspend fun unreadCount(authentication: Authentication) =
        browse.unreadCount(authentication.principalOrThrow())

    override suspend fun markRead(id: Long, authentication: Authentication) =
        mutate.markRead(authentication.principalOrThrow(), id)

    override suspend fun markAllRead(authentication: Authentication) =
        mutate.markAllRead(authentication.principalOrThrow())

    override suspend fun delete(id: Long, authentication: Authentication) =
        mutate.delete(authentication.principalOrThrow(), id)

    override suspend fun deleteAll(authentication: Authentication) =
        mutate.deleteAll(authentication.principalOrThrow())
}
