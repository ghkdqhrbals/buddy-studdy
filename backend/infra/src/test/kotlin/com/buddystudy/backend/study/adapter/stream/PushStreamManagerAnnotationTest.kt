package com.buddystudy.backend.study.adapter.stream

import com.buddystudy.auth.domain.entity.ApnsEnvironment
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.RedisStreamObjectPublisher
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.backend.study.adapter.outbound.stream.QuestionPushRequestedPayload
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.PushNotificationPort
import java.time.Instant
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions

class PushStreamManagerAnnotationTest {
    @Test
    fun `push listener declares topic object group consumer and batch settings`() {
        val annotation = PushStreamManager::class.declaredFunctions
            .single { it.name == "consumePush" }
            .findAnnotation<StreamListener>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.NOTIFICATION_QUESTION_PUSH_REQUESTED)
        assertThat(annotation.payloadType).isEqualTo(QuestionPushRequestedPayload::class)
        assertThat(annotation.group).isEqualTo("bs-backend-push")
        assertThat(annotation.consumer).isEqualTo("buddystudy-push")
        assertThat(annotation.eventType).isEqualTo("QUESTION_PUSH_REQUESTED")
        assertThat(annotation.batchSize).isEqualTo(50)
        assertThat(annotation.concurrencyProperty).isEqualTo("buddystudy.streams.push-consumer-concurrency")
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK)
    }

    @Test
    fun `push recovery declares idle autoclaim settings`() {
        val annotation = PushStreamManager::class.declaredFunctions
            .single { it.name == "recoverIdlePush" }
            .findAnnotation<StreamScheduler>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.NOTIFICATION_QUESTION_PUSH_REQUESTED)
        assertThat(annotation.payloadType).isEqualTo(QuestionPushRequestedPayload::class)
        assertThat(annotation.group).isEqualTo("bs-backend-push")
        assertThat(annotation.consumer).isEqualTo("buddystudy-push-recovery")
        assertThat(annotation.minIdleTimeMs).isEqualTo(300_000)
        assertThat(annotation.batchSize).isEqualTo(50)
        assertThat(annotation.fixedDelayMs).isEqualTo(30_000)
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK)
    }

    @Test
    fun `push consumer revalidates session and uses the current apns target`() = runBlocking {
        val pushNotifications = mock(PushNotificationPort::class.java)
        val devices = mock(DevicePort::class.java)
        val userDevices = mock(UserDevicePort::class.java)
        val notifications = mock(NotificationPersistencePort::class.java) { invocation ->
            if (invocation.method.name == "markPushSent") 1 else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        `when`(devices.findByDeviceId("device-1")).thenReturn(
            DeviceEntity(
                deviceId = "device-1",
                userId = 11,
                apnsToken = "current-apns-token",
                apnsEnvironment = ApnsEnvironment.SANDBOX,
            ),
        )
        `when`(userDevices.hasActiveSession(11, "device-1")).thenReturn(true)
        val payload = QuestionPushRequestedPayload(
            recordId = 10,
            notificationId = 42,
            studyId = 77,
            deviceId = "device-1",
            userId = 11,
            question = "What is SwiftUI?",
            expectedAnswerHint = "UI framework",
            topic = "SwiftUI",
            difficultyLevel = 5,
            language = "en",
            sound = "default",
            intervalMinutes = 15,
            title = "BuddyStudy",
            body = "What is SwiftUI?",
            deepLink = "buddystudy://records/10",
            createdAt = Instant.parse("2026-06-08T00:00:00Z"),
        )
        val context = StreamMessageContext(
            streamKey = "notification.question-push.requested.v1",
            recordId = "1-0",
            eventId = "question-push-10-device-1",
            eventType = "QUESTION_PUSH_REQUESTED",
            fields = mapOf(
                "pushProvider" to "APNS",
                "apnsToken" to "stale-queued-token",
                "apnsEnvironment" to "production",
            ),
            claimed = false,
        )
        val manager = PushStreamManager(
            properties = BuddyStudyProperties(),
            publisher = mock(RedisStreamObjectPublisher::class.java),
            pushNotifications = pushNotifications,
            devices = devices,
            userDevices = userDevices,
            notifications = notifications,
        )

        manager.deliver(payload, context)

        val sentMessage = mockingDetails(pushNotifications).invocations
            .single { it.method.name == "sendQuestion" }
            .arguments[0]
        assertThat(sentMessage).isInstanceOf(ApnsQuestionMessage::class.java)
        assertThat((sentMessage as ApnsQuestionMessage).notificationId).isEqualTo("42")
        assertThat(sentMessage.token).isEqualTo("current-apns-token")
        assertThat(sentMessage.environment).isEqualTo("sandbox")
        val pushStatus = mockingDetails(notifications).invocations
            .single { it.method.name == "markPushSent" }
        assertThat(pushStatus.arguments[0]).isEqualTo(42L)
        assertThat(pushStatus.arguments[1]).isInstanceOf(Instant::class.java)
    }

    @Test
    fun `queued push is discarded when the user logged out before delivery`() = runBlocking {
        val pushNotifications = mock(PushNotificationPort::class.java)
        val notifications = mock(NotificationPersistencePort::class.java)
        val devices = mock(DevicePort::class.java)
        val userDevices = mock(UserDevicePort::class.java)
        `when`(devices.findByDeviceId("device-1")).thenReturn(
            DeviceEntity(
                deviceId = "device-1",
                userId = null,
                apnsToken = "still-present-device-token",
            ),
        )
        val manager = PushStreamManager(
            properties = BuddyStudyProperties(),
            publisher = mock(RedisStreamObjectPublisher::class.java),
            pushNotifications = pushNotifications,
            devices = devices,
            userDevices = userDevices,
            notifications = notifications,
        )
        val payload = QuestionPushRequestedPayload(
            recordId = 12,
            notificationId = 42,
            studyId = 77,
            deviceId = "device-1",
            userId = 11,
            question = "What is SwiftUI?",
            expectedAnswerHint = "UI framework",
            topic = "SwiftUI",
            difficultyLevel = 5,
            language = "en",
            sound = "default",
            intervalMinutes = 15,
            title = "BuddyStudy",
            body = "What is SwiftUI?",
            deepLink = "buddystudy://records/12",
            createdAt = Instant.parse("2026-06-08T00:00:00Z"),
        )
        val context = StreamMessageContext(
            streamKey = "notification.question-push.requested.v1",
            recordId = "1-0",
            eventId = "question-push-12-device-1",
            eventType = "QUESTION_PUSH_REQUESTED",
            fields = mapOf(
                "pushProvider" to "APNS",
                "apnsToken" to "queued-before-logout",
                "apnsEnvironment" to "sandbox",
            ),
            claimed = true,
        )

        manager.deliver(payload, context)

        verifyNoInteractions(pushNotifications)
        val failure = mockingDetails(notifications).invocations
            .single { it.method.name == "markPushFailed" }
        assertThat(failure.arguments[0]).isEqualTo(42L)
        assertThat(failure.arguments[1]).isEqualTo("Push target device is not attached to the user.")
    }
}
