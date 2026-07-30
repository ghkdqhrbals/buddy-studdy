package com.buddystudy.backend.study.adapter.outbound.apns

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.ApnsAlert
import com.buddystudy.backend.study.application.port.outbound.ApnsAps
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionPayload
import com.buddystudy.backend.common.application.json.JsonMapperProvider
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
    fun `oversized APNs body is truncated by encoded byte size while navigation metadata is retained`() {
        val adapter = ApnsPushNotificationAdapter(BuddyStudyProperties())
        val oversizedBody = """긴 질문 😀 "인용" \ 경로
            |""".trimMargin().repeat(1_000)

        val body = adapter.buildPayloadJson(
            ApnsQuestionMessage(
                recordId = "10",
                notificationId = "99",
                topic = "Swift",
                token = "apns-token",
                environment = "production",
                payload = ApnsQuestionPayload(
                    aps = ApnsAps(
                        alert = ApnsAlert("새 질문 도착", oversizedBody),
                        sound = "default",
                    ),
                    deepLink = "buddystudy://records/10",
                    notificationId = "99",
                ),
            ),
        )

        val json = JsonMapperProvider.mapper.readTree(body)
        assertThat(body.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(4_096)
        assertThat(json.path("deepLink").asText()).isEqualTo("buddystudy://records/10")
        assertThat(json.path("notificationId").asText()).isEqualTo("99")
        assertThat(json.path("aps").path("alert").path("body").asText()).endsWith("…")
        assertThat(json.path("aps").path("alert").path("body").asText()).doesNotContain("\uFFFD")
    }

    @Test
    fun `oversized APNs title is also truncated when body compaction is insufficient`() {
        val adapter = ApnsPushNotificationAdapter(BuddyStudyProperties())

        val body = adapter.buildPayloadJson(
            ApnsQuestionMessage(
                recordId = "10",
                topic = "Swift",
                token = "apns-token",
                environment = "production",
                payload = ApnsQuestionPayload(
                    aps = ApnsAps(
                        alert = ApnsAlert("알림 제목 😀".repeat(1_000), "질문"),
                        sound = "default",
                    ),
                    deepLink = "buddystudy://records/10",
                ),
            ),
        )

        val json = JsonMapperProvider.mapper.readTree(body)
        assertThat(body.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(4_096)
        assertThat(json.path("aps").path("alert").path("title").asText()).endsWith("…")
        assertThat(json.path("aps").path("alert").path("title").asText()).doesNotContain("\uFFFD")
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
