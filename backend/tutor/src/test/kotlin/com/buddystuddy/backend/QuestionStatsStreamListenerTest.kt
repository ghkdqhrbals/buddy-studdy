package com.buddystuddy.backend

import com.buddystuddy.backend.community.adapter.outbound.stream.PublicQuestionReactionRedisStreamPublisher
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.community.adapter.inbound.stream.QuestionStatsStreamListener
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.redisstream.consumer.ProducerRoutingShard
import com.redisstream.producer.ProducerRoute
import com.redisstream.producer.PublishedRedisStreamMessage
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.util.stream.Stream

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystuddy-streams;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "buddystuddy.scheduler.enabled=false",
        "buddystuddy.streams.enabled=false",
        "buddystuddy.crypto.master-key=test-master-key",
        "buddystuddy.auth.jwt-secret=test-jwt-secret",
        "spring.autoconfigure.exclude=com.redisstream.RedisStreamCoordinatorAutoConfiguration,com.redisstream.producer.ProducerRoutingAutoConfiguration,com.redisstream.consumer.CoordinatorConsumerAutoConfiguration",
    ]
)
class QuestionStatsStreamListenerTest {
    @Autowired lateinit var listener: QuestionStatsStreamListener
    @Autowired lateinit var stats: QuestionStatsPort

    @Test
    fun `view events increment question view count`() {
        stats.save(QuestionStatsEntity(questionId = 101))

        listener.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "101"))
        listener.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "101"))

        assertThat(stats.findById(101).orElseThrow().viewCount).isEqualTo(2)
    }

    @Test
    fun `like and comment action events update stats counts`() {
        stats.save(QuestionStatsEntity(questionId = 202))

        listener.processActionEvent(mapOf("eventType" to "QUESTION_LIKED", "questionId" to "202"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_COMMENTED", "questionId" to "202"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_COMMENT_DELETED", "questionId" to "202"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_UNLIKED", "questionId" to "202"))

        val updated = stats.findById(202).orElseThrow()
        assertThat(updated.likeCount).isEqualTo(0)
        assertThat(updated.commentCount).isEqualTo(0)
    }

    @Test
    fun `decrement action events never move stats counters below zero`() {
        stats.save(QuestionStatsEntity(questionId = 203))

        listener.processActionEvent(mapOf("eventType" to "QUESTION_UNLIKED", "questionId" to "203"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_COMMENT_DELETED", "questionId" to "203"))

        val updated = stats.findById(203).orElseThrow()
        assertThat(updated.likeCount).isZero()
        assertThat(updated.commentCount).isZero()
    }

    @Test
    fun `published reaction fields are consumable by stats listener`() {
        stats.save(QuestionStatsEntity(questionId = 606))
        val viewPublisher = RecordingPublisher()
        val actionPublisher = RecordingPublisher()
        val publisher = reactionPublisher(viewPublisher, actionPublisher)

        assertThat(publisher.publishViewed(606, 10)).isTrue()
        assertThat(publisher.publishViewed(606, null)).isTrue()
        assertThat(publisher.publishLiked(606, 10)).isTrue()
        assertThat(publisher.publishLiked(606, 11)).isTrue()
        assertThat(publisher.publishUnliked(606, 10)).isTrue()
        assertThat(publisher.publishCommented(606, 11)).isTrue()

        viewPublisher.requests.forEach { listener.processViewEvent(it.fields) }
        actionPublisher.requests.forEach { listener.processActionEvent(it.fields) }

        val updated = stats.findById(606).orElseThrow()
        assertThat(updated.viewCount).isEqualTo(2)
        assertThat(updated.likeCount).isEqualTo(1)
        assertThat(updated.commentCount).isEqualTo(1)
    }

    @Test
    fun `mixed stats events create missing row and converge to expected counts`() {
        listener.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "707"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_LIKED", "questionId" to "707"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_LIKED", "questionId" to "707"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_UNLIKED", "questionId" to "707"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_COMMENTED", "questionId" to "707"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_COMMENTED", "questionId" to "707"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_COMMENT_DELETED", "questionId" to "707"))

        val updated = stats.findById(707).orElseThrow()
        assertThat(updated.viewCount).isEqualTo(1)
        assertThat(updated.likeCount).isEqualTo(1)
        assertThat(updated.commentCount).isEqualTo(1)
    }

    @Test
    fun `stream event creates stats row when stats row is missing`() {
        listener.processActionEvent(mapOf("eventType" to "QUESTION_LIKED", "questionId" to "303"))

        assertThat(stats.findById(303).orElseThrow().likeCount).isEqualTo(1)
    }

    @Test
    fun `view event accepts record id fallback`() {
        stats.save(QuestionStatsEntity(questionId = 404))

        listener.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "recordId" to "404"))

        assertThat(stats.findById(404).orElseThrow().viewCount).isEqualTo(1)
    }

    @Test
    fun `invalid ids and unknown action events are ignored`() {
        stats.save(QuestionStatsEntity(questionId = 505, likeCount = 2, commentCount = 3, viewCount = 4))

        listener.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "not-a-number"))
        listener.processActionEvent(mapOf("eventType" to "UNKNOWN", "questionId" to "505"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_LIKED"))

        val updated = stats.findById(505).orElseThrow()
        assertThat(updated.likeCount).isEqualTo(2)
        assertThat(updated.commentCount).isEqualTo(3)
        assertThat(updated.viewCount).isEqualTo(4)
    }

    private fun reactionPublisher(
        viewPublisher: RedisStreamPublisher,
        actionPublisher: RedisStreamPublisher,
    ): PublicQuestionReactionRedisStreamPublisher {
        val properties = BuddyStuddyProperties().apply {
            streams.enabled = true
        }
        return PublicQuestionReactionRedisStreamPublisher(
            properties,
            provider(viewPublisher),
            provider(actionPublisher),
        )
    }

    private fun provider(publisher: RedisStreamPublisher): ObjectProvider<RedisStreamPublisher> =
        object : ObjectProvider<RedisStreamPublisher> {
            override fun getObject(): RedisStreamPublisher = publisher
            override fun getIfAvailable(): RedisStreamPublisher = publisher
            override fun iterator(): MutableIterator<RedisStreamPublisher> = mutableListOf(publisher).iterator()
            override fun stream(): Stream<RedisStreamPublisher> = Stream.of(publisher)
        }

    private data class PublishRequest(
        val key: String,
        val fields: Map<String, String>,
        val options: RedisStreamPublishOptions,
    )

    private class RecordingPublisher : RedisStreamPublisher {
        val requests = mutableListOf<PublishRequest>()

        override fun publish(
            partitionKey: String,
            fields: Map<String, String>,
            options: RedisStreamPublishOptions,
        ): PublishedRedisStreamMessage {
            requests += PublishRequest(partitionKey, fields, options)
            val streamKey = "stream-$partitionKey"
            return PublishedRedisStreamMessage(
                streamKey,
                "record-1",
                ProducerRoute(streamKey, ProducerRoutingShard(0, streamKey, 0), 1),
            )
        }
    }
}
