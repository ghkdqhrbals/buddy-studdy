package com.buddystudy.backend.study

import kotlinx.coroutines.runBlocking

import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.auth.domain.entity.ApnsEnvironment
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.study.application.model.PushTestCommand
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.PushNotificationPort
import com.buddystudy.backend.study.application.port.outbound.PushQuestionMessage
import com.buddystudy.backend.study.application.service.PushTestService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PushTestServiceTest {
    @Test
    fun `send test push uses authenticated device apns token`(): Unit = runBlocking {
        val devices = FakeDevicePort(
            DeviceEntity(
                deviceId = "dev-1",
                userId = 1,
                apnsToken = "apns-token",
                apnsEnvironment = ApnsEnvironment.SANDBOX,
            )
        )
        val push = CapturingPushNotificationPort()
        val service = PushTestService(devices, push, CapturingNotificationPublisher())

        val response = service.sendTestPush(
            Principal(userId = 1, deviceId = "dev-1", sessionId = 10, anonymous = false),
            PushTestCommand(
                title = "BuddyStudy",
                body = "Push test body",
                topic = "Swift",
                recordId = "record-1",
                sound = "default",
                deepLink = "buddystudy://records/record-1",
            ),
        )

        val message = push.message as ApnsQuestionMessage
        assertThat(response.sent).isTrue()
        assertThat(response.provider).isEqualTo("APNS")
        assertThat(response.deviceId).isEqualTo("dev-1")
        assertThat(message.token).isEqualTo("apns-token")
        assertThat(message.environment).isEqualTo("sandbox")
        assertThat(message.recordId).isEqualTo("record-1")
        assertThat(message.topic).isEqualTo("Swift")
        assertThat(message.payload.aps.alert.title).isEqualTo("BuddyStudy")
        assertThat(message.payload.aps.alert.body).isEqualTo("Push test body")
        assertThat(message.payload.deepLink).isEqualTo("buddystudy://records/record-1")
    }

    @Test
    fun `send test push fails when apns token is missing`(): Unit = runBlocking {
        val service = PushTestService(
            FakeDevicePort(DeviceEntity(deviceId = "dev-1", userId = 1, apnsToken = "")),
            CapturingPushNotificationPort(),
            CapturingNotificationPublisher(),
        )

        assertThatThrownBy {
            runBlocking {
                service.sendTestPush(
                    Principal(userId = 1, deviceId = "dev-1", sessionId = 10, anonymous = false),
                    PushTestCommand(),
                )
            }
        }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.VALIDATION_ERROR)
    }

    @Test
    fun `publish test push event sends one notification with push enabled`(): Unit = runBlocking {
        val notifications = CapturingNotificationPublisher()
        val service = PushTestService(FakeDevicePort(), CapturingPushNotificationPort(), notifications)
        val response = service.publishTestPushEvent(
            Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false),
            PushTestCommand(
                title = "Title",
                body = "Body",
                topic = "Redis",
                recordId = "123",
                studyId = 55,
                difficultyLevel = 9,
                language = "en",
                sound = "default",
                deepLink = "buddystudy://studies/55",
            )
        )

        assertThat(response.sent).isTrue()
        assertThat(response.provider).isEqualTo("NOTIFICATION_STREAM")
        assertThat(response.recordId).isEqualTo("123")
        val request = notifications.commands.single()
        assertThat(request.deviceId).isEqualTo("dev-1")
        assertThat(request.userId).isEqualTo(7)
        assertThat(request.type).isEqualTo("ADMIN_MESSAGE")
        assertThat(request.shouldPush).isTrue()
        assertThat(request.title).isEqualTo("Title")
        assertThat(request.body).isEqualTo("Body")
        assertThat(request.deepLink).isEqualTo("buddystudy://studies/55")
        assertThat(request.metadataJson).contains("\"recordId\":123")
        assertThat(request.metadataJson).contains("\"studyId\":55")
        assertThat(request.metadataJson).contains("\"topic\":\"Redis\"")
    }

    private class FakeDevicePort(private val device: DeviceEntity? = null) : DevicePort {
        override suspend fun save(entity: DeviceEntity): DeviceEntity = entity
        override suspend fun findByDeviceId(deviceId: String): DeviceEntity? = device?.takeIf { it.deviceId == deviceId }
        override suspend fun findByInstallationKeyHash(installationKeyHash: String): DeviceEntity? =
            device?.takeIf { it.installationKeyHash == installationKeyHash }
        override suspend fun findAllByUserId(userId: Long): List<DeviceEntity> =
            device?.takeIf { it.userId == userId }?.let { listOf(it) }.orEmpty()
    }

    private class CapturingPushNotificationPort : PushNotificationPort {
        lateinit var message: PushQuestionMessage

        override suspend fun sendQuestion(message: PushQuestionMessage) {
            this.message = message
        }
    }

    private class CapturingNotificationPublisher : PublishNotificationUseCase {
        val commands = mutableListOf<NotificationRequestCommand>()

        override suspend fun publish(command: NotificationRequestCommand): Boolean {
            commands += command
            return true
        }
    }
}
