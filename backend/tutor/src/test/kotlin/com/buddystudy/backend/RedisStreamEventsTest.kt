package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.community.application.model.CommunityQuestionEvent
import com.buddystudy.backend.study.adapter.outbound.stream.QuestionPushRequestedEvent
import com.buddystudy.backend.study.adapter.outbound.stream.toPayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class RedisStreamEventsTest {
    @Test
    fun `push event creates a typed payload without manual field conversion`(): Unit = runBlocking {
        val event = QuestionPushRequestedEvent(
            recordId = 1,
            studyId = 10,
            deviceId = "device-1",
            userId = null,
            question = "What is SwiftUI?",
            expectedAnswerHint = null,
            topic = "SwiftUI",
            difficultyLevel = 5,
            language = "en",
            sound = "",
            intervalMinutes = 15,
            createdAt = Instant.ofEpochSecond(120),
            eventId = "event-push",
        )

        val payload = event.toPayload()

        assertThat(payload.recordId).isEqualTo(1)
        assertThat(payload.studyId).isEqualTo(10)
        assertThat(payload.deviceId).isEqualTo("device-1")
        assertThat(payload.question).isEqualTo("What is SwiftUI?")
        assertThat(payload.topic).isEqualTo("SwiftUI")
        assertThat(payload.difficultyLevel).isEqualTo(5)
        assertThat(payload.createdAt).isEqualTo(Instant.ofEpochSecond(120))
    }

    @Test
    fun `view event preserves localization metadata in the outbox payload`() {
        val event = CommunityQuestionEvent(
            eventId = "event-1",
            questionId = 10,
            userId = 20,
            translationState = "TRANSLATED",
            translationLanguage = "ja",
            translationReason = "EXPLICIT_TL",
            requestId = "request-1",
            questionSourceLanguage = "en",
            questionDisplayLanguage = "ja",
            answerSourceLanguage = "ko",
            answerDisplayLanguage = "ja",
            aiResponseSourceLanguage = "en",
            aiResponseDisplayLanguage = "ja",
            occurredAt = Instant.ofEpochSecond(180),
        )

        assertThat(event.eventId).isEqualTo("event-1")
        assertThat(event.questionId).isEqualTo(10)
        assertThat(event.translationLanguage).isEqualTo("ja")
        assertThat(event.questionSourceLanguage).isEqualTo("en")
        assertThat(event.questionDisplayLanguage).isEqualTo("ja")
        assertThat(event.answerSourceLanguage).isEqualTo("ko")
        assertThat(event.aiResponseDisplayLanguage).isEqualTo("ja")
        assertThat(event.occurredAt).isEqualTo(Instant.ofEpochSecond(180))
    }
}
