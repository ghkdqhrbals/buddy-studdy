package com.buddystudy.backend.notification.adapter.inbound.stream

import com.buddystudy.auth.domain.entity.ApnsEnvironment
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.auth.domain.entity.UserDeviceEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationResult
import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.common.application.outbox.PublishedStreamRecord
import com.buddystudy.backend.notification.adapter.outbound.stream.NotificationRequestedPayload
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.ProcessNotificationEventUseCase
import com.buddystudy.backend.notification.application.port.inbound.RecoverNotificationCommandUseCase
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.backend.notification.application.service.NotificationSendPolicy
import com.buddystudy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation

class NotificationStreamListenerAnnotationTest {
    @Test
    fun `notification listener uses typed Jackson payload and ACK`() {
        val annotation = NotificationStreamListener::class.declaredFunctions
            .single { it.name == "consumeNotification" }
            .findAnnotation<StreamListener>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.NOTIFICATION_MESSAGE_REQUESTED)
        assertThat(annotation.group).isEqualTo("bs-backend-notification")
        assertThat(annotation.eventType).isEqualTo("NOTIFICATION_REQUESTED")
        assertThat(annotation.payloadType).isEqualTo(NotificationRequestedPayload::class)
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK)
    }

    @Test
    fun `notification recovery uses same typed payload and ACK`() {
        val annotation = NotificationStreamListener::class.declaredFunctions
            .single { it.name == "recoverIdleNotification" }
            .findAnnotation<StreamScheduler>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.NOTIFICATION_MESSAGE_REQUESTED)
        assertThat(annotation.group).isEqualTo("bs-backend-notification")
        assertThat(annotation.eventType).isEqualTo("NOTIFICATION_REQUESTED")
        assertThat(annotation.payloadType).isEqualTo(NotificationRequestedPayload::class)
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK)
        assertThat(annotation.minIdleTimeMs).isEqualTo(300_000)
    }

    @Test
    fun `legacy notification payload uses envelope event id and omitted field defaults`() {
        val payload = JsonMapperProvider.mapper.readValue<NotificationRequestedPayload>(
            """
            {
              "userId": 7,
              "title": "New answer",
              "body": "A user answered.",
              "threadType": "QUESTION",
              "threadId": "45",
              "deepLink": "buddystudy://questions/45",
              "metadataJson": null
            }
            """.trimIndent(),
        )
        val context = StreamMessageContext(
            streamKey = "notification.message.requested.v1",
            recordId = "1785259240567-0",
            eventId = "question-created-45",
            eventType = "NOTIFICATION_REQUESTED",
            fields = emptyMap(),
            claimed = true,
        )

        val command = payload.toCommandOrNull(context.eventId!!)
        assertThat(command?.eventId).isEqualTo("question-created-45")
        assertThat(command?.type).isEqualTo("ACTIVITY")
        assertThat(command?.shouldPush).isFalse()
    }

    @Test
    fun `empty native payload is decoded for question notification recovery`() {
        val payload = JsonMapperProvider.mapper.readValue<NotificationRequestedPayload>("{}")

        assertThat(payload.toCommandOrNull("question-created-45")).isNull()
        assertThat(payload.missingRequiredFields())
            .containsExactly("eventId", "owner", "title", "body")
    }

    @Test
    fun `study question is persisted once then published through its notification push option`(): Unit = runBlocking {
        var processed = 0
        val pushes = mutableListOf<QuestionPushRequest>()
        val listener = NotificationStreamListener(
            processor = object : ProcessNotificationEventUseCase {
                override suspend fun process(command: NotificationRequestCommand): Long {
                    processed += 1
                    return 42
                }
            },
            notifications = mock(NotificationPersistencePort::class.java),
            devices = devicePort(
                DeviceEntity(
                    deviceId = "device-1",
                    userId = 7,
                    apnsToken = "token",
                    apnsEnvironment = ApnsEnvironment.SANDBOX,
                ),
            ),
            userDevices = userDevicePort(
                UserDeviceEntity(userId = 7, deviceId = "device-1"),
            ),
            pushPublisher = object : QuestionPushPublishPort {
                override suspend fun publishPush(request: QuestionPushRequest): PublishedStreamRecord {
                    pushes += request
                    return PublishedStreamRecord("notification.question-push.requested.v1", "2-0")
                }
            },
            notificationRecovery = object : RecoverNotificationCommandUseCase {
                override suspend fun recover(eventId: String): NotificationRequestCommand? = null
            },
            notificationSendPolicy = NotificationSendPolicy(
                object : PermissionEvaluator {
                    override suspend fun evaluate(
                        principal: Principal,
                        permissionCode: String,
                    ): PermissionEvaluationResult = PermissionEvaluationResult.granted(permissionCode)
                },
            ),
        )
        val payload = NotificationRequestedPayload(
            eventId = "question-created-10",
            userId = 7,
            type = "STUDY_QUESTION",
            title = "New question",
            body = "What is a Redis consumer group?",
            threadType = "study_question",
            threadId = "10",
            deepLink = "buddystudy://records/10",
            metadataJson = """{"recordId":10,"studyId":3,"topic":"Redis","difficultyLevel":5,"language":"en"}""",
            shouldPush = true,
        )

        listener.deliver(
            payload,
            StreamMessageContext(
                streamKey = "notification.message.requested.v1",
                recordId = "1-0",
                eventId = payload.eventId,
                eventType = "NOTIFICATION_REQUESTED",
                fields = emptyMap(),
                claimed = false,
            ),
        )

        assertThat(processed).isEqualTo(1)
        assertThat(pushes).hasSize(1)
        assertThat(pushes.single().notificationId).isEqualTo(42)
        assertThat(pushes.single().recordId).isEqualTo(10)
        assertThat(pushes.single().deviceId).isEqualTo("device-1")
    }

    private fun devicePort(device: DeviceEntity) = object : DevicePort {
        override suspend fun save(entity: DeviceEntity): DeviceEntity = entity
        override suspend fun findByDeviceId(deviceId: String): DeviceEntity? =
            device.takeIf { it.deviceId == deviceId }
        override suspend fun findByInstallationKeyHash(installationKeyHash: String): DeviceEntity? = null
        override suspend fun findAllByUserId(userId: Long): List<DeviceEntity> =
            listOf(device).filter { it.userId == userId }
    }

    private fun userDevicePort(session: UserDeviceEntity) = object : UserDevicePort {
        override suspend fun save(entity: UserDeviceEntity): UserDeviceEntity = entity
        override suspend fun findByUserIdAndDeviceId(userId: Long, deviceId: String): UserDeviceEntity? =
            session.takeIf { it.userId == userId && it.deviceId == deviceId }
        override suspend fun findByIdAndUserId(id: Long, userId: Long): UserDeviceEntity? = null
        override suspend fun findActiveByUserId(userId: Long): List<UserDeviceEntity> =
            listOf(session).filter { it.userId == userId }
        override suspend fun hasActiveSession(userId: Long, deviceId: String): Boolean =
            session.userId == userId && session.deviceId == deviceId
    }
}
