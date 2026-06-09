package com.buddystuddy.backend.study

import com.buddystuddy.auth.domain.entity.DeviceEntity
import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.study.application.model.PushTestCommand
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystuddy.backend.study.application.port.outbound.PushNotificationPort
import com.buddystuddy.backend.study.application.port.outbound.PushQuestionMessage
import com.buddystuddy.backend.study.application.service.PushTestService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PushTestServiceTest {
    @Test
    fun `send test push uses authenticated device apns token`() {
        val devices = FakeDevicePort(
            DeviceEntity(
                deviceId = "dev-1",
                userId = 1,
                apnsToken = "apns-token",
                apnsEnvironment = "sandbox",
            )
        )
        val push = CapturingPushNotificationPort()
        val service = PushTestService(devices, push)

        val response = service.sendTestPush(
            Principal(userId = 1, deviceId = "dev-1", sessionId = 10, anonymous = false),
            PushTestCommand(
                title = "BuddyStuddy",
                body = "Push test body",
                topic = "Swift",
                recordId = "record-1",
                sound = "default",
                deepLink = "buddystuddy://records/record-1",
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
        assertThat(message.payload.aps.alert.title).isEqualTo("BuddyStuddy")
        assertThat(message.payload.aps.alert.body).isEqualTo("Push test body")
        assertThat(message.payload.deepLink).isEqualTo("buddystuddy://records/record-1")
    }

    @Test
    fun `send test push fails when apns token is missing`() {
        val service = PushTestService(
            FakeDevicePort(DeviceEntity(deviceId = "dev-1", userId = 1, apnsToken = "")),
            CapturingPushNotificationPort(),
        )

        assertThatThrownBy {
            service.sendTestPush(
                Principal(userId = 1, deviceId = "dev-1", sessionId = 10, anonymous = false),
                PushTestCommand(),
            )
        }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.VALIDATION_ERROR)
    }

    private class FakeDevicePort(private val device: DeviceEntity?) : DevicePort {
        override fun save(entity: DeviceEntity): DeviceEntity = entity
        override fun findByDeviceId(deviceId: String): DeviceEntity? = device?.takeIf { it.deviceId == deviceId }
    }

    private class CapturingPushNotificationPort : PushNotificationPort {
        lateinit var message: PushQuestionMessage

        override fun sendQuestion(message: PushQuestionMessage) {
            this.message = message
        }
    }
}
