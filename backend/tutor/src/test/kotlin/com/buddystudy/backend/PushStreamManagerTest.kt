package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

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
import org.mockito.Mockito.mock
import java.time.Instant

class PushStreamManagerTest {
    @Test
    fun `publish methods return false when streams are disabled`(): Unit = runBlocking {
        val publisher = RecordingPublisher()
        val service = service(enabled = false, publisher = publisher)

        assertThat(service.publishPush(pushEvent())).isFalse()
        assertThat(publisher.requests).isEmpty()
    }

    @Test
    fun `push event publishes to configured stream`(): Unit = runBlocking {
        val publisher = RecordingPublisher()
        val service = service(enabled = true, publisher = publisher)

        assertThat(service.publishPush(pushEvent(topic = "SwiftUI"))).isTrue()

        val request = publisher.requests.single()
        assertThat(request.topic).isEqualTo(RedisStreamTopic.DOMAIN_EVENTS)
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
    }

    @Test
    fun `publish methods return false when publisher throws`(): Unit = runBlocking {
        val service = service(enabled = true, publisher = RecordingPublisher(fail = true))

        assertThat(service.publishPush(pushEvent())).isFalse()
    }

    private fun service(enabled: Boolean, publisher: RedisStreamObjectPublisher): PushStreamManager {
        val properties = BuddyStudyProperties().apply {
            streams.enabled = enabled
            streams.key = "buddystudy-events-v1"
        }
        return PushStreamManager(
            properties = properties,
            publisher = publisher,
            pushNotifications = mock(PushNotificationPort::class.java),
            devices = mock(DevicePort::class.java),
            userDevices = mock(UserDevicePort::class.java),
            notifications = mock(NotificationPersistencePort::class.java),
        )
    }

    private fun pushEvent(
        topic: String = "SwiftUI",
        deviceId: String = "device-1",
        userId: Long? = 11,
    ) = QuestionPushRequest(
        recordId = 10,
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
            return RedisStreamPublishedMessage(topic.apiName, "record-1")
        }
    }
}
