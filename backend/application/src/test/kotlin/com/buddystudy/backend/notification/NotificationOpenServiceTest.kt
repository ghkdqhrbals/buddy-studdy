package com.buddystudy.backend.notification

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.notification.application.model.NotificationOpenSource
import com.buddystudy.backend.notification.application.port.outbound.NotificationOpenPersistencePort
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.backend.notification.application.service.NotificationOpenService
import com.buddystudy.notification.domain.entity.AppNotificationEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant

class NotificationOpenServiceTest {
    private val notifications = Mockito.mock(NotificationPersistencePort::class.java)
    private val opens = Opens()
    private val service = NotificationOpenService(notifications, opens)
    private val principal = Principal(10, "device-a", 1, false)

    @Test
    fun `owner can explicitly open from multiple devices without duplicate clicks or read mutation`(): Unit = runBlocking {
        val notification = AppNotificationEntity(id = 5, userId = 10)
        Mockito.`when`(notifications.findByIdAndUserIdAndDeletedAtIsNull(5, 10)).thenReturn(notification)
        service.open(principal, 5, NotificationOpenSource.PUSH)
        service.open(principal.copy(deviceId = "device-b"), 5, NotificationOpenSource.PUSH)
        service.open(principal, 5, NotificationOpenSource.INBOX)
        assertThat(opens.values).containsExactlyInAnyOrder(5L to NotificationOpenSource.PUSH, 5L to NotificationOpenSource.INBOX)
        assertThat(notification.readAt).isNull()
    }

    @Test
    fun `unowned anonymous and deleted user notifications cannot be attributed`(): Unit = runBlocking {
        for (viewer in listOf(principal, principal.copy(anonymous = true))) {
            assertThatThrownBy { runBlocking { service.open(viewer, 8, NotificationOpenSource.PUSH) } }
                .isInstanceOf(ApiException::class.java)
        }
        assertThat(opens.values).isEmpty()
    }

    private class Opens : NotificationOpenPersistencePort {
        val values = mutableSetOf<Pair<Long, NotificationOpenSource>>()
        override suspend fun markOpen(notificationId: Long, source: NotificationOpenSource, now: Instant) { values += notificationId to source }
    }
}
