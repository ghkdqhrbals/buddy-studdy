package com.buddystudy.backend

import com.buddystudy.backend.community.adapter.inbound.stream.QuestionStatsStreamEventHandler
import com.buddystudy.backend.community.application.model.CommunityQuestionEvent
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@SpringBootTest
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ]
)
class QuestionStatsStreamListenerTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var handler: QuestionStatsStreamEventHandler
    @Autowired lateinit var stats: QuestionStatsPort

    @Test
    fun `view events increment question view count exactly once per event id`(): Unit = runBlocking {
        stats.save(QuestionStatsEntity(questionId = 101))

        handler.processViewEvent(event("view-1", 101), STREAM_KEY)
        handler.processViewEvent(event("view-2", 101), STREAM_KEY)
        handler.processViewEvent(event("view-2", 101), STREAM_KEY)

        assertThat(stats.findById(101)!!.viewCount).isEqualTo(2)
    }

    @Test
    fun `view event creates stats row when stats row is missing`(): Unit = runBlocking {
        handler.processViewEvent(event("view-3", 707), STREAM_KEY)

        assertThat(stats.findById(707)!!.viewCount).isEqualTo(1)
    }

    private fun event(eventId: String, questionId: Long) = CommunityQuestionEvent(
        eventId = eventId,
        questionId = questionId,
        userId = null,
        occurredAt = Instant.parse("2026-07-30T00:00:00Z"),
    )

    private companion object {
        const val STREAM_KEY = "community.question.viewed.v1"
    }
}
