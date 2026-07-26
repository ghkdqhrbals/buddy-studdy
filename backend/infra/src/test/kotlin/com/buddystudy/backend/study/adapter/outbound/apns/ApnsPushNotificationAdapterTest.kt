package com.buddystudy.backend.study.adapter.outbound.apns

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.ApnsAlert
import com.buddystudy.backend.study.application.port.outbound.ApnsAps
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionPayload
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class ApnsPushNotificationAdapterTest {
    @Test
    fun `apns request timeout is five seconds`(): Unit = runBlocking {
        val adapter = ApnsPushNotificationAdapter(BuddyStudyProperties())

        val request = adapter.buildRequest(
            message = ApnsQuestionMessage(
                recordId = "10",
                topic = "Swift",
                token = "apns-token",
                environment = "sandbox",
                payload = ApnsQuestionPayload(
                    aps = ApnsAps(
                        alert = ApnsAlert("BuddyStudy", "Question?"),
                        sound = "default",
                    ),
                    deepLink = "buddystudy://studies/77",
                ),
            ),
            jwt = "jwt-token",
        )

        assertThat(request.timeout()).hasValue(Duration.ofSeconds(5))
    }

    @Test
    fun `apns payload includes unread badge when provided`(): Unit = runBlocking {
        val adapter = ApnsPushNotificationAdapter(BuddyStudyProperties())

        val body = adapter.buildPayloadJson(
            ApnsQuestionMessage(
                recordId = "10",
                topic = "Swift",
                token = "apns-token",
                environment = "sandbox",
                payload = ApnsQuestionPayload(
                    aps = ApnsAps(
                        alert = ApnsAlert("BuddyStudy", "New comment"),
                        sound = "default",
                        badge = 7,
                    ),
                    deepLink = "buddystudy://notifications/10",
                ),
            )
        )

        assertThat(body).contains(""""badge":7""")
    }

    @Test
    fun `apns payload includes notification id when provided`(): Unit = runBlocking {
        val adapter = ApnsPushNotificationAdapter(BuddyStudyProperties())

        val body = adapter.buildPayloadJson(
            ApnsQuestionMessage(
                recordId = "10",
                notificationId = "99",
                topic = "Swift",
                token = "apns-token",
                environment = "sandbox",
                payload = ApnsQuestionPayload(
                    aps = ApnsAps(
                        alert = ApnsAlert("BuddyStudy", "New comment"),
                        sound = "default",
                    ),
                    deepLink = "buddystudy://notifications/99",
                    notificationId = "99",
                ),
            )
        )

        assertThat(body).contains(""""notificationId":"99"""")
    }

    @Test
    fun `missing APNs token is a delivery failure`(): Unit = runBlocking {
        val adapter = ApnsPushNotificationAdapter(BuddyStudyProperties())
        val message = ApnsQuestionMessage(
            recordId = "10",
            topic = "Swift",
            token = "",
            environment = "sandbox",
            payload = ApnsQuestionPayload(
                aps = ApnsAps(
                    alert = ApnsAlert("BuddyStudy", "Question?"),
                    sound = "default",
                ),
                deepLink = "buddystudy://records/10",
            ),
        )

        assertThatThrownBy {
            runBlocking { adapter.sendQuestion(message) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("APNs token is missing")
    }
}
