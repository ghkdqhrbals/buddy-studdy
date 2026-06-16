package com.buddystuddy.backend.notification

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystuddy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystuddy.backend.notification.application.port.outbound.NotificationStreamPublishPort
import com.buddystuddy.backend.notification.application.service.NotificationService
import com.buddystuddy.notification.domain.entity.AppNotificationEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Instant

class NotificationServiceTest {
    private val store = FakeNotificationStore()
    private val publisher = FakeNotificationPublisher()
    private val service = NotificationService(store, publisher)
    private val principal = Principal(userId = 10, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `processing same event id creates one notification`() {
        val command = NotificationRequestCommand(
            eventId = "event-1",
            userId = 10,
            title = "New comment",
            body = "Hello",
            threadType = "question",
            threadId = "100",
            shouldPush = true,
        )

        val firstId = service.process(command)
        val secondId = service.process(command)

        assertThat(secondId).isEqualTo(firstId)
        assertThat(store.rows).hasSize(1)
        assertThat(store.rows.single().shouldPush).isTrue()
    }

    @Test
    fun `list excludes deleted notifications and returns unread count`() {
        store.save(AppNotificationEntity(eventId = "read", userId = 10, title = "Read", body = "Body", readAt = Instant.now()))
        store.save(AppNotificationEntity(eventId = "unread", userId = 10, title = "Unread", body = "Body"))
        store.save(AppNotificationEntity(eventId = "deleted", userId = 10, title = "Deleted", body = "Body", deletedAt = Instant.now()))
        store.save(AppNotificationEntity(eventId = "other", userId = 11, title = "Other", body = "Body"))

        val page = service.notifications(principal, limit = 30, offset = 0)

        assertThat(page.notifications.map { it.title }).containsExactly("Unread", "Read")
        assertThat(page.unreadCount).isEqualTo(1)
        assertThat(page.totalCount).isEqualTo(2)
    }

    @Test
    fun `mark read and delete mutate only owner notification`() {
        val notification = store.save(AppNotificationEntity(eventId = "n1", userId = 10, title = "Title", body = "Body"))

        service.markRead(principal, notification.id)
        service.delete(principal, notification.id)

        assertThat(store.rows.single().readAt).isNotNull()
        assertThat(store.rows.single().deletedAt).isNotNull()
        assertThat(service.unreadCount(principal).unreadCount).isZero()
    }

    @Test
    fun `publish delegates to stream publisher`() {
        val command = NotificationRequestCommand(eventId = "event-2", userId = 10, title = "Title", body = "Body")

        assertThat(service.publish(command)).isTrue()
        assertThat(publisher.commands).containsExactly(command)
    }

    private class FakeNotificationStore : NotificationPersistencePort {
        val rows = mutableListOf<AppNotificationEntity>()
        private var nextId = 1L

        override fun save(entity: AppNotificationEntity): AppNotificationEntity {
            if (entity.id == 0L) entity.id = nextId++
            rows.removeIf { it.id == entity.id }
            rows += entity
            return entity
        }

        override fun findByEventId(eventId: String): AppNotificationEntity? =
            rows.firstOrNull { it.eventId == eventId }

        override fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): AppNotificationEntity? =
            rows.firstOrNull { it.id == id && it.userId == userId && it.deletedAt == null }

        override fun findByUserIdAndDeletedAtIsNull(userId: Long, pageable: Pageable): Page<AppNotificationEntity> {
            val filtered = rows
                .filter { it.userId == userId && it.deletedAt == null }
                .sortedWith(compareByDescending<AppNotificationEntity> { it.createdAt }.thenByDescending { it.id })
            return PageImpl(filtered.drop(pageable.offset.toInt()).take(pageable.pageSize), pageable, filtered.size.toLong())
        }

        override fun countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId: Long): Long =
            rows.count { it.userId == userId && it.readAt == null && it.deletedAt == null }.toLong()

        override fun markAllDeleted(userId: Long, deletedAt: Instant): Int {
            var count = 0
            rows.filter { it.userId == userId && it.deletedAt == null }.forEach {
                it.deletedAt = deletedAt
                count += 1
            }
            return count
        }

        override fun claimPush(id: Long, now: Instant, staleBefore: Instant): Int {
            val row = rows.firstOrNull {
                it.id == id &&
                    it.shouldPush &&
                    it.pushSentAt == null &&
                    (it.pushClaimedAt == null || it.pushClaimedAt!!.isBefore(staleBefore))
            } ?: return 0
            row.pushClaimedAt = now
            return 1
        }

        override fun markPushSent(id: Long, now: Instant): Int {
            rows.firstOrNull { it.id == id }?.pushSentAt = now
            return 1
        }

        override fun markPushFailed(id: Long, error: String, now: Instant): Int {
            rows.firstOrNull { it.id == id }?.pushError = error
            return 1
        }
    }

    private class FakeNotificationPublisher : NotificationStreamPublishPort {
        val commands = mutableListOf<NotificationRequestCommand>()
        override fun publishNotification(command: NotificationRequestCommand): Boolean {
            commands += command
            return true
        }
    }
}
