package com.buddystuddy.backend

import com.buddystuddy.backend.community.adapter.outbound.stream.PublicQuestionViewedEvent
import com.buddystuddy.backend.study.adapter.outbound.stream.QuestionPushRequestedEvent
import com.buddystuddy.backend.study.adapter.outbound.stream.toRedisStreamFields
import com.buddystuddy.utils.toStringMapWithoutNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class RedisStreamEventsTest {
    @Test
    fun `push event publishes envelope with full payload json`() {
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

        val fields = event.toRedisStreamFields()

        assertThat(fields).containsKeys("eventId", "eventType", "payload")
        assertThat(fields["eventId"]).isEqualTo("event-push")
        assertThat(fields["eventType"]).isEqualTo("QUESTION_PUSH_REQUESTED")
        assertThat(fields["payload"].orEmpty())
            .contains("\"recordId\":1")
            .contains("\"studyId\":10")
            .contains("\"deviceId\":\"device-1\"")
            .contains("\"question\":\"What is SwiftUI?\"")
            .contains("\"topic\":\"SwiftUI\"")
            .contains("\"difficultyLevel\":5")
            .contains("\"createdAt\":\"1970-01-01T00:02:00Z\"")
        assertThat(fields).doesNotContainKeys("recordId", "question", "topic")
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
