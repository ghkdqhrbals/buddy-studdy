package com.buddystuddy.backend.study.adapter.outbound.apns

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.application.port.outbound.ApnsAlert
import com.buddystuddy.backend.study.application.port.outbound.ApnsAps
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionPayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class ApnsPushNotificationAdapterTest {
    @Test
    fun `apns request timeout is five seconds`() {
        val adapter = ApnsPushNotificationAdapter(BuddyStuddyProperties())

        val request = adapter.buildRequest(
            message = ApnsQuestionMessage(
                recordId = "10",
                topic = "Swift",
                token = "apns-token",
                environment = "sandbox",
                payload = ApnsQuestionPayload(
                    aps = ApnsAps(
                        alert = ApnsAlert("BuddyStuddy", "Question?"),
                        sound = "default",
                    ),
                    deepLink = "buddystuddy://studies/77",
                ),
            ),
            jwt = "jwt-token",
        )

        assertThat(request.timeout()).hasValue(Duration.ofSeconds(5))
    }

    @Test
    fun `apns payload includes unread badge when provided`() {
        val adapter = ApnsPushNotificationAdapter(BuddyStuddyProperties())

        val body = adapter.buildPayloadJson(
            ApnsQuestionMessage(
                recordId = "10",
                topic = "Swift",
                token = "apns-token",
                environment = "sandbox",
                payload = ApnsQuestionPayload(
                    aps = ApnsAps(
                        alert = ApnsAlert("BuddyStuddy", "New comment"),
                        sound = "default",
                        badge = 7,
                    ),
                    deepLink = "buddystuddy://notifications/10",
                ),
            )
        )

        assertThat(body).contains(""""badge":7""")
    }
}
