package com.buddystuddy.backend

import com.buddystuddy.backend.community.adapter.inbound.stream.QuestionStatsStreamEventHandler
import com.buddystuddy.backend.community.adapter.outbound.stream.PublicQuestionReactionRedisStreamPublisher
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
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
    @Autowired lateinit var handler: QuestionStatsStreamEventHandler
    @Autowired lateinit var stats: QuestionStatsPort

    @Test
    fun `view events increment question view count`() {
        stats.save(QuestionStatsEntity(questionId = 101))

        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "101"))
        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "101"))

        assertThat(stats.findById(101).orElseThrow().viewCount).isEqualTo(2)
    }

    @Test
    fun `published view fields are consumable by stats listener`() {
        stats.save(QuestionStatsEntity(questionId = 606))
        val viewPublisher = RecordingPublisher()
        val publisher = reactionPublisher(viewPublisher)

        assertThat(publisher.publishViewed(606, 10)).isTrue()
        assertThat(publisher.publishViewed(606, null)).isTrue()

        viewPublisher.requests.forEach { handler.processViewEvent(it.fields) }

        val updated = stats.findById(606).orElseThrow()
        assertThat(updated.viewCount).isEqualTo(2)
        assertThat(updated.likeCount).isZero()
        assertThat(updated.commentCount).isZero()
    }

    @Test
    fun `view event creates stats row when stats row is missing`() {
        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "707"))

        assertThat(stats.findById(707).orElseThrow().viewCount).isEqualTo(1)
    }

    @Test
    fun `view event accepts record id fallback`() {
        stats.save(QuestionStatsEntity(questionId = 404))

        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "recordId" to "404"))

        assertThat(stats.findById(404).orElseThrow().viewCount).isEqualTo(1)
    }

    @Test
    fun `invalid view ids are ignored`() {
        stats.save(QuestionStatsEntity(questionId = 505, likeCount = 2, commentCount = 3, viewCount = 4))

        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "not-a-number"))
        handler.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED"))

        val updated = stats.findById(505).orElseThrow()
        assertThat(updated.likeCount).isEqualTo(2)
        assertThat(updated.commentCount).isEqualTo(3)
        assertThat(updated.viewCount).isEqualTo(4)
    }

    private fun reactionPublisher(
        viewPublisher: RedisStreamPublisher,
    ): PublicQuestionReactionRedisStreamPublisher {
        val properties = BuddyStuddyProperties().apply {
            streams.enabled = true
        }
        return PublicQuestionReactionRedisStreamPublisher(
            properties,
            provider(viewPublisher),
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
        val key: String?,
        val fields: Map<String, String>,
        val options: RedisStreamPublishOptions,
    )

    private class RecordingPublisher : RedisStreamPublisher {
        val requests = mutableListOf<PublishRequest>()

        override fun publish(
            partitionKey: String?,
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
