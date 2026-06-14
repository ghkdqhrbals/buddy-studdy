package com.buddystuddy.backend

import com.buddystuddy.backend.community.adapter.outbound.stream.PublicQuestionViewedEvent
import com.buddystuddy.backend.study.adapter.outbound.stream.QuestionPushRequestedEvent
import com.buddystuddy.utils.toStringMapWithoutNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class RedisStreamEventsTest {
    @Test
    fun `push event uses the same object to string map conversion`() {
        val event = QuestionPushRequestedEvent(
            recordId = 1,
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

        assertThat(event.toStringMapWithoutNull()).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "eventId" to "event-push",
                "eventType" to "QUESTION_PUSH_REQUESTED",
                "recordId" to "1",
                "deviceId" to "device-1",
                "question" to "What is SwiftUI?",
                "topic" to "SwiftUI",
                "difficultyLevel" to "5",
                "language" to "en",
                "intervalMinutes" to "15",
                "createdAt" to "1970-01-01T00:02:00Z",
            )
        )
    }

    @Test
    fun `view event exposes consistent stream field map`() {
        val event = PublicQuestionViewedEvent(
            questionId = 10,
            userId = 20,
            createdAt = Instant.ofEpochSecond(180),
            eventId = "event-1",
        )

        assertThat(event.toStringMapWithoutNull()).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "eventId" to "event-1",
                "eventType" to "CONTENT_VIEWED",
                "questionId" to "10",
                "userId" to "20",
                "minuteBucket" to "3",
                "createdAt" to "1970-01-01T00:03:00Z",
            )
        )
    }

}
