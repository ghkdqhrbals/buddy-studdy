package com.buddystuddy.backend

import com.buddystuddy.utils.toStringMapWithoutNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class MapUtilsTest {
    data class SamplePayload(
        val questionId: Long,
        val userId: Long?,
        val eventType: String,
        val createdAt: Instant,
        val blank: String = "",
    )

    @Test
    fun `object converts to string map without null or blank values`() {
        val result = SamplePayload(
            questionId = 10,
            userId = null,
            eventType = "QUESTION_LIKED",
            createdAt = Instant.ofEpochSecond(60),
        ).toStringMapWithoutNull()

        assertThat(result).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "questionId" to "10",
                "eventType" to "QUESTION_LIKED",
                "createdAt" to "1970-01-01T00:01:00Z",
            )
        )
    }

    @Test
    fun `map converts to string map without null or blank values`() {
        val result = mapOf(
            "questionId" to 20L,
            "userId" to null,
            "eventType" to "CONTENT_VIEWED",
            "blank" to "",
        ).toStringMapWithoutNull()

        assertThat(result).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "questionId" to "20",
                "eventType" to "CONTENT_VIEWED",
            )
        )
    }
}
