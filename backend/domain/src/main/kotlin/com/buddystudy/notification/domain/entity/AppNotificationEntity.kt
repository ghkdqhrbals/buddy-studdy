package com.buddystudy.notification.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("app_notifications")
class AppNotificationEntity(
    @Id
    var id: Long = 0,
    var eventId: String = "",
    var userId: Long? = null,
    var deviceId: String? = null,
    var actorUserId: Long? = null,
    var type: String = "ACTIVITY",
    var title: String = "",
    var body: String = "",
    var threadType: String? = null,
    var threadId: String? = null,
    var deepLink: String? = null,
    var metadataJson: String? = null,
    var shouldPush: Boolean = false,
    var pushClaimedAt: Instant? = null,
    var pushSentAt: Instant? = null,
    var pushError: String? = null,
    var readAt: Instant? = null,
    var deletedAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
