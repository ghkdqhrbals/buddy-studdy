package com.buddystudy.backend.study.adapter.stream

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
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
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
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

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
    fun `direct question push creates notification and sends apns from push topic`() = runBlocking {
        val pushNotifications = mock(PushNotificationPort::class.java)
        val devices = mock(DevicePort::class.java)
        val userDevices = mock(UserDevicePort::class.java)
        val notifications = mock(NotificationPersistencePort::class.java)
        val notificationProcessor = mock(ProcessNotificationEventUseCase::class.java)
        val notificationSendPolicy = mock(NotificationSendPolicy::class.java)
        val payload = QuestionPushRequestedPayload(
            recordId = 10,
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
            fields = emptyMap(),
            claimed = false,
        )
        `when`(notificationProcessor.process(any(NotificationRequestCommand::class.java))).thenReturn(42L)
        `when`(notificationSendPolicy.canSendPush(any(NotificationRequestCommand::class.java))).thenReturn(true)
        `when`(notifications.claimPush(eq(42L), any(Instant::class.java), any(Instant::class.java))).thenReturn(1)
        `when`(userDevices.hasActiveSession(11, "device-1")).thenReturn(true)
        `when`(devices.findByDeviceId("device-1")).thenReturn(
            DeviceEntity(deviceId = "device-1", apnsToken = "apns-token", apnsEnvironment = "sandbox")
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

        val commandCaptor = ArgumentCaptor.forClass(NotificationRequestCommand::class.java)
        verify(notificationProcessor).process(commandCaptor.capture())
        assertThat(commandCaptor.value.eventId).isEqualTo("question-created-10")
        assertThat(commandCaptor.value.shouldPush).isTrue()
        val messageCaptor = ArgumentCaptor.forClass(com.buddystudy.backend.study.application.port.outbound.PushQuestionMessage::class.java)
        verify(pushNotifications).sendQuestion(messageCaptor.capture())
        assertThat(messageCaptor.value).isInstanceOf(ApnsQuestionMessage::class.java)
        assertThat(messageCaptor.value.notificationId).isEqualTo("42")
        verify(notifications).markPushSent(eq(42L), any(Instant::class.java))
    }
}
