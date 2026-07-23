package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.community.adapter.inbound.stream.QuestionStatsStreamEventHandler
import com.buddystudy.backend.community.adapter.outbound.stream.PublicQuestionReactionRedisStreamPublisher
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishedMessage
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ]
)
class QuestionStatsStreamListenerTest : PostgresIntegrationTestSupport() {
    @Autowired lateinit var handler: QuestionStatsStreamEventHandler
    @Autowired lateinit var stats: QuestionStatsPort

    @Test
    fun `view events increment question view count`(): Unit = runBlocking {
        stats.save(QuestionStatsEntity(questionId = 101))

        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "101"))
        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "101"))

        assertThat(stats.findById(101)!!.viewCount).isEqualTo(2)
    }

    @Test
    fun `published view fields are consumable by stats listener`(): Unit = runBlocking {
        stats.save(QuestionStatsEntity(questionId = 606))
        val viewPublisher = RecordingPublisher()
        val publisher = reactionPublisher(viewPublisher)

        assertThat(publisher.publishViewed(606, 10)).isTrue()
        assertThat(publisher.publishViewed(606, null)).isTrue()

        viewPublisher.requests.forEach { handler.processViewEvent(it.fields) }

        val updated = stats.findById(606)!!
        assertThat(updated.viewCount).isEqualTo(2)
        assertThat(updated.likeCount).isZero()
        assertThat(updated.commentCount).isZero()
    }

    @Test
    fun `view event creates stats row when stats row is missing`(): Unit = runBlocking {
        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "707"))

        assertThat(stats.findById(707)!!.viewCount).isEqualTo(1)
    }

    @Test
    fun `view event accepts record id fallback`(): Unit = runBlocking {
        stats.save(QuestionStatsEntity(questionId = 404))

        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "recordId" to "404"))

        assertThat(stats.findById(404)!!.viewCount).isEqualTo(1)
    }

    @Test
    fun `invalid view ids are ignored`(): Unit = runBlocking {
        stats.save(QuestionStatsEntity(questionId = 505, likeCount = 2, commentCount = 3, viewCount = 4))

        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "not-a-number"))
        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED"))

        val updated = stats.findById(505)!!
        assertThat(updated.likeCount).isEqualTo(2)
        assertThat(updated.commentCount).isEqualTo(3)
        assertThat(updated.viewCount).isEqualTo(4)
    }

    private fun reactionPublisher(
        viewPublisher: RedisStreamPublishOperations,
    ): PublicQuestionReactionRedisStreamPublisher {
        val properties = BuddyStudyProperties().apply {
            streams.enabled = true
        }
        return PublicQuestionReactionRedisStreamPublisher(properties, viewPublisher)
    }

    private data class PublishRequest(
        val streamKey: String,
        val fields: Map<String, String>,
    )

    private class RecordingPublisher : RedisStreamPublishOperations {
        val requests = mutableListOf<PublishRequest>()

        override suspend fun publish(streamKey: String, fields: Map<String, String>): RedisStreamPublishedMessage {
            requests += PublishRequest(streamKey, fields)
            return RedisStreamPublishedMessage(streamKey, "record-1")
        }
    }
}
