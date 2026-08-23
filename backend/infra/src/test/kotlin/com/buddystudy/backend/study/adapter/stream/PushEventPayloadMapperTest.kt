package com.buddystudy.backend.study.adapter.stream

import com.buddystudy.backend.study.adapter.outbound.stream.QuestionPushRequestedPayload
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.PushMessageType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class PushEventPayloadMapperTest {
    @Test
    fun `typed payload maps to apns question message`() {
        val message = PushEventPayloadMapper.toPushQuestionMessage(
            payload = payload(),
            fields = emptyMap(),
            apnsToken = "apns-token",
            apnsEnvironment = "sandbox",
        )

        assertThat(message.type).isEqualTo(PushMessageType.APNS)
        val apns = message as ApnsQuestionMessage
        assertThat(apns.recordId).isEqualTo("10")
        assertThat(apns.topic).isEqualTo("SwiftUI")
        assertThat(apns.token).isEqualTo("apns-token")
        assertThat(apns.environment).isEqualTo("sandbox")
        assertThat(apns.payload.aps.alert.title).isEqualTo("BuddyStudy")
        assertThat(apns.payload.aps.alert.body).isEqualTo("What is SwiftUI?")
        assertThat(apns.payload.aps.sound).isEqualTo("ping.aiff")
        assertThat(apns.payload.deepLink).isEqualTo("buddystudy://records/10")
    }

    @Test
    fun `provider metadata can select fcm without changing typed payload`() {
        val message = PushEventPayloadMapper.toPushQuestionMessage(
            payload = payload(),
            fields = mapOf(
                "pushProvider" to "FCM",
                "fcmToken" to "fcm-token",
            ),
            apnsToken = "apns-token",
            apnsEnvironment = "production",
        )

        assertThat(message.type).isEqualTo(PushMessageType.FCM)
        assertThat(message.recordId).isEqualTo("10")
    }

    @Test
    fun `notification id is retained until APNs delivery completes`() {
        val message = PushEventPayloadMapper.toPushQuestionMessage(
            payload = payload(notificationId = 99),
            fields = emptyMap(),
            apnsToken = "apns-token",
            apnsEnvironment = "production",
        )

        assertThat((message as ApnsQuestionMessage).notificationId).isEqualTo("99")
    }

    private fun payload(notificationId: Long? = null) = QuestionPushRequestedPayload(
        recordId = 10,
        notificationId = notificationId,
        studyId = 77,
        deviceId = "device-1",
        userId = 11,
        question = "What is SwiftUI?",
        expectedAnswerHint = "UI framework",
        topic = "SwiftUI",
        difficultyLevel = 5,
        language = "en",
        sound = "ping.aiff",
        intervalMinutes = 15,
        title = null,
        body = null,
        deepLink = null,
        createdAt = Instant.parse("2026-06-08T00:00:00Z"),
    )
}
