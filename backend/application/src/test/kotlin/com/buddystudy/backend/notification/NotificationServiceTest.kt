package com.buddystudy.backend.notification

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.backend.notification.application.port.outbound.NotificationStreamPublishPort
import com.buddystudy.backend.notification.application.service.NotificationService
import com.buddystudy.notification.domain.entity.AppNotificationEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    fun `processing same event id creates one notification`(): Unit = runBlocking {
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
    fun `list excludes deleted notifications and returns unread count`(): Unit = runBlocking {
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
    fun `anonymous device can list device notifications without user scoped notifications`(): Unit = runBlocking {
        val anonymous = Principal(userId = 20, deviceId = "dev-anon", sessionId = 2, anonymous = true)
        store.save(AppNotificationEntity(eventId = "device", userId = null, deviceId = "dev-anon", title = "Device", body = "Body"))
        store.save(AppNotificationEntity(eventId = "other-device", userId = null, deviceId = "dev-other", title = "Other device", body = "Body"))
        store.save(AppNotificationEntity(eventId = "user", userId = 20, title = "User", body = "Body"))

        val page = service.notifications(anonymous, limit = 30, offset = 0)

        assertThat(page.notifications.map { it.title }).containsExactly("Device")
        assertThat(page.unreadCount).isEqualTo(1)
        assertThat(page.totalCount).isEqualTo(1)
    }

    @Test
    fun `signed in principal lists user notifications before device notifications`(): Unit = runBlocking {
        store.save(AppNotificationEntity(eventId = "device", userId = null, deviceId = "dev-1", title = "Device", body = "Body"))
        store.save(AppNotificationEntity(eventId = "user", userId = 10, title = "User", body = "Body"))
        store.save(AppNotificationEntity(eventId = "other-device", userId = null, deviceId = "dev-other", title = "Other device", body = "Body"))

        val page = service.notifications(principal, limit = 30, offset = 0)

        assertThat(page.notifications.map { it.title }).containsExactly("User", "Device")
        assertThat(page.unreadCount).isEqualTo(2)
        assertThat(page.totalCount).isEqualTo(2)
    }

    @Test
    fun `mark read for user notification marks all user devices by thread`(): Unit = runBlocking {
        val first = store.save(AppNotificationEntity(eventId = "u1", userId = 10, deviceId = "dev-1", threadType = "comment", threadId = "100", title = "First", body = "Body"))
        val second = store.save(AppNotificationEntity(eventId = "u2", userId = 10, deviceId = "dev-2", threadType = "comment", threadId = "100", title = "Second", body = "Body"))
        store.save(AppNotificationEntity(eventId = "device", userId = null, deviceId = "dev-1", threadType = "comment", threadId = "100", title = "Device", body = "Body"))

        service.markRead(principal, first.id)

        assertThat(first.readAt).isNotNull()
        assertThat(second.readAt).isNotNull()
        assertThat(store.rows.single { it.eventId == "device" }.readAt).isNull()
    }

    @Test
    fun `mark read for device notification only marks current device notification`(): Unit = runBlocking {
        val anonymous = Principal(userId = 20, deviceId = "dev-anon", sessionId = 2, anonymous = true)
        val currentDevice = store.save(AppNotificationEntity(eventId = "device", userId = null, deviceId = "dev-anon", threadType = "question", threadId = "200", title = "Device", body = "Body"))
        store.save(AppNotificationEntity(eventId = "other-device", userId = null, deviceId = "dev-other", threadType = "question", threadId = "200", title = "Other", body = "Body"))

        service.markRead(anonymous, currentDevice.id)

        assertThat(currentDevice.readAt).isNotNull()
        assertThat(store.rows.single { it.eventId == "other-device" }.readAt).isNull()
    }

    @Test
    fun `mark read and delete mutate only owner notification`(): Unit = runBlocking {
        val notification = store.save(AppNotificationEntity(eventId = "n1", userId = 10, title = "Title", body = "Body"))

        service.markRead(principal, notification.id)
        service.delete(principal, notification.id)

        assertThat(store.rows.single().readAt).isNotNull()
        assertThat(store.rows.single().deletedAt).isNotNull()
        assertThat(service.unreadCount(principal).unreadCount).isZero()
    }

    @Test
    fun `publish delegates to stream publisher`(): Unit = runBlocking {
        val command = NotificationRequestCommand(eventId = "event-2", userId = 10, title = "Title", body = "Body")

        assertThat(service.publish(command)).isTrue()
        assertThat(publisher.commands).containsExactly(command)
    }

    @Test
    fun `process rejects notification without user or device owner`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { service.process(NotificationRequestCommand(eventId = "orphan", title = "Title", body = "Body")) }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private class FakeNotificationStore : NotificationPersistencePort {
        val rows = mutableListOf<AppNotificationEntity>()
        private var nextId = 1L

        override suspend fun save(entity: AppNotificationEntity): AppNotificationEntity {
            if (entity.id == 0L) entity.id = nextId++
            rows.removeIf { it.id == entity.id }
            rows += entity
            return entity
        }

        override suspend fun findByEventId(eventId: String): AppNotificationEntity? =
            rows.firstOrNull { it.eventId == eventId }

        override suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): AppNotificationEntity? =
            rows.firstOrNull { it.id == id && it.userId == userId && it.deletedAt == null }

        override suspend fun findByIdAndDeviceIdAndUserIdIsNullAndDeletedAtIsNull(id: Long, deviceId: String): AppNotificationEntity? =
            rows.firstOrNull { it.id == id && it.deviceId == deviceId && it.userId == null && it.deletedAt == null }

        override suspend fun findByUserIdAndDeletedAtIsNull(userId: Long, pageable: Pageable): Page<AppNotificationEntity> {
            val filtered = rows
                .filter { it.userId == userId && it.deletedAt == null }
                .sortedWith(compareByDescending<AppNotificationEntity> { it.createdAt }.thenByDescending { it.id })
            return PageImpl(filtered.drop(pageable.offset.toInt()).take(pageable.pageSize), pageable, filtered.size.toLong())
        }

        override suspend fun findVisible(userId: Long?, deviceId: String, pageable: Pageable): Page<AppNotificationEntity> {
            val filtered = rows
                .filter {
                    it.deletedAt == null &&
                        ((userId != null && it.userId == userId) || (it.userId == null && it.deviceId == deviceId))
                }
                .sortedWith(
                    compareBy<AppNotificationEntity> { if (it.userId != null) 0 else 1 }
                        .thenByDescending { it.createdAt }
                        .thenByDescending { it.id }
                )
            return PageImpl(filtered.drop(pageable.offset.toInt()).take(pageable.pageSize), pageable, filtered.size.toLong())
        }

        override suspend fun countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId: Long): Long =
            rows.count { it.userId == userId && it.readAt == null && it.deletedAt == null }.toLong()

        override suspend fun countVisibleUnread(userId: Long?, deviceId: String): Long =
            rows.count {
                it.readAt == null &&
                    it.deletedAt == null &&
                    ((userId != null && it.userId == userId) || (it.userId == null && it.deviceId == deviceId))
            }.toLong()

        override suspend fun markAllDeleted(userId: Long, deletedAt: Instant): Int {
            var count = 0
            rows.filter { it.userId == userId && it.deletedAt == null }.forEach {
                it.deletedAt = deletedAt
                count += 1
            }
            return count
        }

        override suspend fun markVisibleDeleted(userId: Long?, deviceId: String, deletedAt: Instant): Int {
            var count = 0
            rows.filter {
                it.deletedAt == null &&
                    ((userId != null && it.userId == userId) || (it.userId == null && it.deviceId == deviceId))
            }.forEach {
                it.deletedAt = deletedAt
                count += 1
            }
            return count
        }

        override suspend fun markUserThreadRead(userId: Long, threadType: String, threadId: String, readAt: Instant): Int {
            var count = 0
            rows.filter {
                it.userId == userId &&
                    it.threadType == threadType &&
                    it.threadId == threadId &&
                    it.readAt == null &&
                    it.deletedAt == null
            }.forEach {
                it.readAt = readAt
                count += 1
            }
            return count
        }

        override suspend fun claimPush(id: Long, now: Instant, staleBefore: Instant): Int {
            val row = rows.firstOrNull {
                it.id == id &&
                    it.shouldPush &&
                    it.pushSentAt == null &&
                    (it.pushClaimedAt == null || it.pushClaimedAt!!.isBefore(staleBefore))
            } ?: return 0
            row.pushClaimedAt = now
            return 1
        }

        override suspend fun markPushSent(id: Long, now: Instant): Int {
            rows.firstOrNull { it.id == id }?.pushSentAt = now
            return 1
        }

        override suspend fun markPushFailed(id: Long, error: String, now: Instant): Int {
            rows.firstOrNull { it.id == id }?.pushError = error
            return 1
        }
    }

    private class FakeNotificationPublisher : NotificationStreamPublishPort {
        val commands = mutableListOf<NotificationRequestCommand>()
        override suspend fun publishNotification(command: NotificationRequestCommand): Boolean {
            commands += command
            return true
        }
    }
}
