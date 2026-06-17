package com.buddystuddy.backend.study.adapter.inbound.stream

import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystuddy.backend.study.application.port.outbound.PushMessageType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PushEventPayloadParserTest {
    @Test
    fun `payload json is parsed into apns question message`() {
        val payload = """
            {
              "recordId":10,
              "studyId":77,
              "deviceId":"device-1",
              "userId":11,
              "question":"What is SwiftUI?",
              "expectedAnswerHint":"UI framework",
              "topic":"SwiftUI",
              "difficultyLevel":5,
              "language":"en",
              "sound":"ping.aiff",
              "intervalMinutes":15,
              "createdAt":"2026-06-08T00:00:00Z"
            }
        """.trimIndent()

        val message = PushEventPayloadParser.toPushQuestionMessage(
            fields = mapOf(
                "eventId" to "event-1",
                "eventType" to "QUESTION_PUSH_REQUESTED",
                "payload" to payload,
            ),
            apnsToken = "apns-token",
            apnsEnvironment = "sandbox",
        )

        assertThat(message.type).isEqualTo(PushMessageType.APNS)
        val apns = message as ApnsQuestionMessage
        assertThat(apns.recordId).isEqualTo("10")
        assertThat(apns.topic).isEqualTo("SwiftUI")
        assertThat(apns.token).isEqualTo("apns-token")
        assertThat(apns.environment).isEqualTo("sandbox")
        assertThat(apns.payload.aps.alert.title).isEqualTo("BuddyStuddy")
        assertThat(apns.payload.aps.alert.body).isEqualTo("What is SwiftUI?")
        assertThat(apns.payload.aps.sound).isEqualTo("ping.aiff")
        assertThat(apns.payload.deepLink).isEqualTo("buddystuddy://records/10")
    }

    @Test
    fun `legacy flat fields remain readable during stream migration`() {
        val message = PushEventPayloadParser.toPushQuestionMessage(
            fields = mapOf(
                "recordId" to "10",
                "studyId" to "77",
                "question" to "Legacy question",
                "topic" to "Kotlin",
                "sound" to "default",
            ),
            apnsToken = "apns-token",
            apnsEnvironment = "production",
        )

        val apns = message as ApnsQuestionMessage
        assertThat(apns.payload.aps.alert.body).isEqualTo("Legacy question")
        assertThat(apns.payload.deepLink).isEqualTo("buddystuddy://records/10")
    }
}
