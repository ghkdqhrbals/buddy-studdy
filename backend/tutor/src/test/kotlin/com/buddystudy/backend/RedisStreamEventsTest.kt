package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.community.adapter.outbound.stream.PublicQuestionViewedEvent
import com.buddystudy.backend.study.adapter.outbound.stream.QuestionPushRequestedEvent
import com.buddystudy.backend.study.adapter.outbound.stream.toPayload
import com.buddystudy.utils.toStringMapWithoutNull
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
    fun `view event exposes consistent stream field map`(): Unit = runBlocking {
        val event = PublicQuestionViewedEvent(
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
            createdAt = Instant.ofEpochSecond(180),
            eventId = "event-1",
        )

        assertThat(event.toStringMapWithoutNull()).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "eventId" to "event-1",
                "eventType" to "CONTENT_VIEWED",
                "questionId" to "10",
                "userId" to "20",
                "translationState" to "TRANSLATED",
                "translationLanguage" to "ja",
                "translationReason" to "EXPLICIT_TL",
                "requestId" to "request-1",
                "questionSourceLanguage" to "en",
                "questionDisplayLanguage" to "ja",
                "answerSourceLanguage" to "ko",
                "answerDisplayLanguage" to "ja",
                "aiResponseSourceLanguage" to "en",
                "aiResponseDisplayLanguage" to "ja",
                "minuteBucket" to "3",
                "createdAt" to "1970-01-01T00:03:00Z",
            )
        )
    }

}
