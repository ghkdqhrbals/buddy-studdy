package com.buddystudy.backend.study.adapter.stream

import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.RedisStreamObjectPublisher
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.notification.application.port.inbound.ProcessNotificationEventUseCase
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.backend.notification.application.service.NotificationSendPolicy
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
import org.mockito.Mockito.verifyNoInteractions

class PushStreamManagerAnnotationTest {
    @Test
    fun `push listener declares topic object group consumer and batch settings`() {
        val annotation = PushStreamManager::class.declaredFunctions
            .single { it.name == "consumePush" }
            .findAnnotation<StreamListener>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.PUSH_EVENTS)
        assertThat(annotation.payloadType).isEqualTo(QuestionPushRequestedPayload::class)
        assertThat(annotation.group).isEqualTo("bs-backend-push")
        assertThat(annotation.consumer).isEqualTo("buddystudy-push")
        assertThat(annotation.eventType).isEqualTo("QUESTION_PUSH_REQUESTED")
        assertThat(annotation.batchSize).isEqualTo(50)
        assertThat(annotation.concurrencyProperty).isEqualTo("buddystudy.streams.push-consumer-concurrency")
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK_DEL)
    }

    @Test
    fun `push recovery declares idle autoclaim settings`() {
        val annotation = PushStreamManager::class.declaredFunctions
            .single { it.name == "recoverIdlePush" }
            .findAnnotation<StreamScheduler>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.PUSH_EVENTS)
        assertThat(annotation.payloadType).isEqualTo(QuestionPushRequestedPayload::class)
        assertThat(annotation.group).isEqualTo("bs-backend-push")
        assertThat(annotation.consumer).isEqualTo("buddystudy-push-recovery")
        assertThat(annotation.minIdleTimeMs).isEqualTo(300_000)
        assertThat(annotation.batchSize).isEqualTo(50)
        assertThat(annotation.fixedDelayMs).isEqualTo(30_000)
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK_DEL)
    }

    @Test
    fun `push consumer sends prepared apns message without target or policy validation`() = runBlocking {
        val pushNotifications = mock(PushNotificationPort::class.java)
        val devices = mock(DevicePort::class.java)
        val userDevices = mock(UserDevicePort::class.java)
        val notifications = mock(NotificationPersistencePort::class.java) { invocation ->
            if (invocation.method.name == "markPushSent") 1 else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        val notificationProcessor = mock(ProcessNotificationEventUseCase::class.java)
        val notificationSendPolicy = mock(NotificationSendPolicy::class.java)
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
            streamKey = "buddystudy-push-v1",
            recordId = "1-0",
            eventId = "question-push-10-device-1",
            eventType = "QUESTION_PUSH_REQUESTED",
            fields = mapOf(
                "pushProvider" to "APNS",
                "apnsToken" to "apns-token",
                "apnsEnvironment" to "sandbox",
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
            notificationProcessor = notificationProcessor,
            notificationSendPolicy = notificationSendPolicy,
        )

        manager.deliver(payload, context)

        val sentMessage = mockingDetails(pushNotifications).invocations
            .single { it.method.name == "sendQuestion" }
            .arguments[0]
        assertThat(sentMessage).isInstanceOf(ApnsQuestionMessage::class.java)
        assertThat((sentMessage as ApnsQuestionMessage).notificationId).isEqualTo("42")
        val pushStatus = mockingDetails(notifications).invocations
            .single { it.method.name == "markPushSent" }
        assertThat(pushStatus.arguments[0]).isEqualTo(42L)
        assertThat(pushStatus.arguments[1]).isInstanceOf(Instant::class.java)
        verifyNoInteractions(devices, userDevices, notificationProcessor, notificationSendPolicy)
    }

    @Test
    fun `legacy push without apns token is marked failed and discarded`() = runBlocking {
        var deliveryAttempts = 0
        val pushNotifications = object : PushNotificationPort {
            override suspend fun sendQuestion(message: com.buddystudy.backend.study.application.port.outbound.PushQuestionMessage) {
                deliveryAttempts += 1
                throw IllegalArgumentException("APNs token is missing for record ${message.recordId}.")
            }
        }
        val notifications = mock(NotificationPersistencePort::class.java)
        val manager = PushStreamManager(
            properties = BuddyStudyProperties(),
            publisher = mock(RedisStreamObjectPublisher::class.java),
            pushNotifications = pushNotifications,
            devices = mock(DevicePort::class.java),
            userDevices = mock(UserDevicePort::class.java),
            notifications = notifications,
            notificationProcessor = mock(ProcessNotificationEventUseCase::class.java),
            notificationSendPolicy = mock(NotificationSendPolicy::class.java),
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
            streamKey = "buddystudy-push-v1",
            recordId = "1-0",
            eventId = "question-push-12-device-1",
            eventType = "QUESTION_PUSH_REQUESTED",
            fields = mapOf(
                "pushProvider" to "APNS",
                "apnsEnvironment" to "sandbox",
            ),
            claimed = true,
        )

        manager.deliver(payload, context)

        assertThat(deliveryAttempts).isEqualTo(1)
        val failure = mockingDetails(notifications).invocations
            .single { it.method.name == "markPushFailed" }
        assertThat(failure.arguments[0]).isEqualTo(42L)
        assertThat(failure.arguments[1]).isEqualTo("APNs token is missing for record 12.")
    }
}
