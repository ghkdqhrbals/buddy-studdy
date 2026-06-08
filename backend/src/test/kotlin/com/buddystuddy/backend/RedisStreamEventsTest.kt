package com.buddystuddy.backend

import com.buddystuddy.backend.stream.QuestionActionEvent
import com.buddystuddy.backend.stream.QuestionStreamEventType
import com.buddystuddy.backend.stream.QuestionViewedEvent
import com.buddystuddy.backend.stream.toStreamMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class RedisStreamEventsTest {
    @Test
    fun `view event exposes consistent stream field map`() {
        val event = QuestionViewedEvent(
            questionId = 10,
            userId = 20,
            viewedAt = Instant.ofEpochSecond(180),
            eventId = "event-1",
        )

        assertThat(event.toStreamMap()).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "eventId" to "event-1",
                "eventType" to "CONTENT_VIEWED",
                "questionId" to 10L,
                "userId" to 20L,
                "minuteBucket" to 3L,
                "createdAt" to "1970-01-01T00:03:00Z",
            )
        )
    }

    @Test
    fun `action event exposes consistent stream field map`() {
        val event = QuestionActionEvent(
            questionId = 30,
            eventType = QuestionStreamEventType.QUESTION_LIKED,
            userId = 40,
            createdAt = Instant.ofEpochSecond(240),
            eventId = "event-2",
        )

        assertThat(event.toStreamMap()).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "eventId" to "event-2",
                "eventType" to "QUESTION_LIKED",
                "questionId" to 30L,
                "userId" to 40L,
                "createdAt" to "1970-01-01T00:04:00Z",
            )
        )
    }
}
