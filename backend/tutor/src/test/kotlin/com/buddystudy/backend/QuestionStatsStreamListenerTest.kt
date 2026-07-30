package com.buddystudy.backend

import com.buddystudy.backend.community.adapter.inbound.stream.QuestionStatsStreamEventHandler
import com.buddystudy.backend.community.application.model.CommunityQuestionEvent
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
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
    @Autowired lateinit var database: DatabaseClient

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

    @Test
    fun `reaction events are retained as idempotent inbox history without changing source of truth counts`(): Unit =
        runBlocking {
            stats.save(QuestionStatsEntity(questionId = 808, likeCount = 4, commentCount = 2))
            val reaction = event("like-1", 808)

            handler.processReactionEvent(reaction, "community.question.liked.v1", LIKE_GROUP, "QUESTION_LIKED")
            handler.processReactionEvent(reaction, "community.question.liked.v1", LIKE_GROUP, "QUESTION_LIKED")

            val row = stats.findById(808)!!
            assertThat(row.likeCount).isEqualTo(4)
            assertThat(row.commentCount).isEqualTo(2)
            val inboxStatus = database.sql(
                """
                select status
                from stream_consumer_inbox
                where event_id = :eventId and consumer_group = :consumerGroup
                """.trimIndent(),
            )
                .bind("eventId", reaction.eventId)
                .bind("consumerGroup", LIKE_GROUP)
                .map { row, _ -> row.get("status", String::class.java)!! }
                .one()
                .awaitSingle()
            assertThat(inboxStatus).isEqualTo("SUCCEEDED")
        }

    private fun event(eventId: String, questionId: Long) = CommunityQuestionEvent(
        eventId = eventId,
        questionId = questionId,
        userId = null,
        occurredAt = Instant.parse("2026-07-30T00:00:00Z"),
    )

    private companion object {
        const val STREAM_KEY = "community.question.viewed.v1"
        const val LIKE_GROUP = "bs-backend-like"
    }
}
