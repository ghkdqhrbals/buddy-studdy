package com.buddystudy.notification.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "app_notifications",
    uniqueConstraints = [UniqueConstraint(name = "uq_app_notifications_event_id", columnNames = ["event_id"])],
    indexes = [
        Index(name = "idx_app_notifications_user_visible_created", columnList = "user_id,deleted_at,created_at,id"),
        Index(name = "idx_app_notifications_user_unread", columnList = "user_id,read_at,deleted_at"),
        Index(name = "idx_app_notifications_device_visible_created", columnList = "device_id,deleted_at,created_at,id"),
        Index(name = "idx_app_notifications_device_unread", columnList = "device_id,read_at,deleted_at"),
        Index(name = "idx_app_notifications_thread", columnList = "thread_type,thread_id"),
    ],
)
class AppNotificationEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "event_id", nullable = false, length = 80)
    var eventId: String = "",
    @Column(name = "user_id")
    var userId: Long? = null,
    @Column(name = "device_id", length = 191)
    var deviceId: String? = null,
    @Column(name = "actor_user_id")
    var actorUserId: Long? = null,
    @Column(nullable = false, length = 64)
    var type: String = "ACTIVITY",
    @Column(nullable = false, length = 160)
    var title: String = "",
    @Column(nullable = false, columnDefinition = "text")
    var body: String = "",
    @Column(name = "thread_type", length = 64)
    var threadType: String? = null,
    @Column(name = "thread_id", length = 120)
    var threadId: String? = null,
    @Column(name = "deep_link", length = 500)
    var deepLink: String? = null,
    @Column(name = "metadata_json", columnDefinition = "text")
    var metadataJson: String? = null,
    @Column(name = "should_push", nullable = false)
    var shouldPush: Boolean = false,
    @Column(name = "push_claimed_at")
    var pushClaimedAt: Instant? = null,
    @Column(name = "push_sent_at")
    var pushSentAt: Instant? = null,
    @Column(name = "push_error", columnDefinition = "text")
    var pushError: String? = null,
    @Column(name = "read_at")
    var readAt: Instant? = null,
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
