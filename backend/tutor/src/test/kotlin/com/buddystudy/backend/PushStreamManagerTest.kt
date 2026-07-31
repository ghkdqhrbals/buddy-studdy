package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.auth.domain.entity.ApnsEnvironment
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishedMessage
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.RedisStreamObjectPublisher
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.adapter.outbound.stream.QuestionPushRequestedPayload
import com.buddystudy.backend.study.adapter.stream.PushStreamManager
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.backend.study.application.port.outbound.PushNotificationPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import java.time.Instant

class PushStreamManagerTest {
    @Test
    fun `publish methods return false when streams are disabled`(): Unit = runBlocking {
        val publisher = RecordingPublisher()
        val service = service(enabled = false, publisher = publisher)

        assertThat(service.publishPush(pushEvent())).isNull()
        assertThat(publisher.requests).isEmpty()
    }

    @Test
    fun `push event publishes to configured stream`(): Unit = runBlocking {
        val publisher = RecordingPublisher()
        val service = service(enabled = true, publisher = publisher)

        val published = service.publishPush(pushEvent(topic = "SwiftUI"))
        assertThat(published).isNotNull
        assertThat(published!!.streamKey).isEqualTo("notification.question-push.requested.v1")
        assertThat(published.recordId).isEqualTo("1-0")

        val request = publisher.requests.single()
        assertThat(request.topic).isEqualTo(RedisStreamTopic.NOTIFICATION_QUESTION_PUSH_REQUESTED)
        assertThat(request.eventType).isEqualTo("QUESTION_PUSH_REQUESTED")
        val payload = request.payload as QuestionPushRequestedPayload
        assertThat(payload.recordId).isEqualTo(10)
        assertThat(payload.studyId).isEqualTo(77)
        assertThat(payload.deviceId).isEqualTo("device-1")
        assertThat(payload.question).isEqualTo("What is SwiftUI?")
        assertThat(payload.expectedAnswerHint).isEqualTo("UI framework")
        assertThat(payload.topic).isEqualTo("SwiftUI")
        assertThat(payload.difficultyLevel).isEqualTo(5)
        assertThat(payload.language).isEqualTo("en")
        assertThat(payload.sound).isEqualTo("default")
        assertThat(payload.intervalMinutes).isEqualTo(15)
        assertThat(payload.notificationId).isEqualTo(42)
        assertThat(request.fields).containsEntry("pushProvider", "APNS")
        assertThat(request.fields).containsEntry("apnsToken", "apns-token")
        assertThat(request.fields).containsEntry("apnsEnvironment", "sandbox")
    }

    @Test
    fun `publish methods return false when publisher throws`(): Unit = runBlocking {
        val service = service(enabled = true, publisher = RecordingPublisher(fail = true))

        assertThat(service.publishPush(pushEvent())).isNull()
    }

    @Test
    fun `push event is rejected before stream publish when apns credentials are missing`(): Unit = runBlocking {
        val publisher = RecordingPublisher()
        val fixture = fixture(enabled = true, publisher = publisher, configureApns = false)

        assertThat(fixture.manager.publishPush(pushEvent())).isNull()

        assertThat(publisher.requests).isEmpty()
        val failure = mockingDetails(fixture.notifications).invocations
            .single { it.method.name == "markPushFailed" }
        assertThat(failure.arguments[0]).isEqualTo(42L)
        assertThat(failure.arguments[1]).isEqualTo("APNs credentials are not configured.")
        assertThat(failure.arguments[2]).isInstanceOf(Instant::class.java)
    }

    private fun service(enabled: Boolean, publisher: RedisStreamObjectPublisher): PushStreamManager {
        return fixture(enabled, publisher).manager
    }

    private fun fixture(
        enabled: Boolean,
        publisher: RedisStreamObjectPublisher,
        configureApns: Boolean = true,
    ): Fixture {
        val properties = BuddyStudyProperties().apply {
            streams.enabled = enabled
            streams.questionPushRequestedKey = "notification.question-push.requested.v1"
            if (configureApns) {
                apns.teamId = "team-id"
                apns.keyId = "key-id"
                apns.authKeyP8 = "private-key"
            }
        }
        val devices = mock(DevicePort::class.java)
        val userDevices = mock(UserDevicePort::class.java)
        val notifications = mock(NotificationPersistencePort::class.java) { invocation ->
            when (invocation.method.name) {
                "claimPush", "markPushSent", "markPushFailed" -> 1
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        runBlocking {
            `when`(userDevices.hasActiveSession(11, "device-1")).thenReturn(true)
            `when`(devices.findByDeviceId("device-1")).thenReturn(
                DeviceEntity(
                    deviceId = "device-1",
                    apnsToken = "apns-token",
                    apnsEnvironment = ApnsEnvironment.SANDBOX,
                ),
            )
        }
        val manager = PushStreamManager(
            properties = properties,
            publisher = publisher,
            pushNotifications = mock(PushNotificationPort::class.java),
            devices = devices,
            userDevices = userDevices,
            notifications = notifications,
        )
        return Fixture(manager, notifications)
    }

    private fun pushEvent(
        topic: String = "SwiftUI",
        deviceId: String = "device-1",
        userId: Long? = 11,
    ) = QuestionPushRequest(
        recordId = 10,
        notificationId = 42,
        studyId = 77,
        deviceId = deviceId,
        userId = userId,
        question = "What is SwiftUI?",
        expectedAnswerHint = "UI framework",
        topic = topic,
        difficultyLevel = 5,
        language = "en",
        sound = "default",
        intervalMinutes = 15,
        createdAt = Instant.parse("2026-06-08T00:00:00Z"),
    )

    private data class PublishRequest(
        val topic: RedisStreamTopic,
        val eventType: String,
        val eventId: String,
        val payload: Any,
        val fields: Map<String, String>,
    )

    private class RecordingPublisher(private val fail: Boolean = false) : RedisStreamObjectPublisher {
        val requests = mutableListOf<PublishRequest>()

        override suspend fun publish(
            topic: RedisStreamTopic,
            eventType: String,
            eventId: String,
            payload: Any,
            fields: Map<String, String>,
        ): RedisStreamPublishedMessage {
            if (fail) throw IllegalStateException("publish failed")
            requests += PublishRequest(topic, eventType, eventId, payload, fields)
            return RedisStreamPublishedMessage(topic.apiName, "1-0")
        }
    }

    private data class Fixture(
        val manager: PushStreamManager,
        val notifications: NotificationPersistencePort,
    )
}
