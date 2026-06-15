package com.buddystuddy.backend

import com.buddystuddy.backend.community.adapter.outbound.stream.PublicQuestionReactionRedisStreamPublisher
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.redisstream.consumer.ProducerRoutingShard
import com.redisstream.producer.ProducerRoute
import com.redisstream.producer.PublishedRedisStreamMessage
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.util.stream.Stream

class PublicQuestionReactionRedisStreamPublisherTest {
    @Test
    fun `publish view returns false when streams are disabled`() {
        val service = service(enabled = false, viewPublisher = RecordingPublisher())

        assertThat(service.publishViewed(1, 2)).isFalse()
    }

    @Test
    fun `enabled streams require view publisher bean`() {
        assertThatThrownBy { service(enabled = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("viewStreamPublisher bean is required")
    }

    @Test
    fun `view event publishes typed field map`() {
        val viewPublisher = RecordingPublisher()
        val service = service(enabled = true, viewPublisher = viewPublisher)

        assertThat(service.publishViewed(20, null)).isTrue()

        val viewRequest = viewPublisher.requests.single()
        assertThat(viewRequest.key).isEqualTo("20")
        assertThat(viewRequest.fields).containsEntry("eventType", "CONTENT_VIEWED")
        assertThat(viewRequest.fields).containsEntry("questionId", "20")
        assertThat(viewRequest.fields).doesNotContainKey("userId")
    }

    @Test
    fun `publish view returns false when publisher throws`() {
        val service = service(enabled = true, viewPublisher = RecordingPublisher(fail = true))

        assertThat(service.publishViewed(1, 2)).isFalse()
    }

    private fun service(
        enabled: Boolean,
        viewPublisher: RedisStreamPublisher? = null,
    ): PublicQuestionReactionRedisStreamPublisher {
        val properties = BuddyStuddyProperties().apply {
            streams.enabled = enabled
        }
        return PublicQuestionReactionRedisStreamPublisher(
            properties,
            provider(viewPublisher),
        )
    }

    private fun provider(publisher: RedisStreamPublisher?): ObjectProvider<RedisStreamPublisher> =
        object : ObjectProvider<RedisStreamPublisher> {
            override fun getObject(): RedisStreamPublisher =
                publisher ?: throw NoSuchElementException("No publisher")

            override fun getIfAvailable(): RedisStreamPublisher? = publisher
            override fun iterator(): MutableIterator<RedisStreamPublisher> = listOfNotNull(publisher).toMutableList().iterator()
            override fun stream(): Stream<RedisStreamPublisher> = listOfNotNull(publisher).stream()
        }

    private data class PublishRequest(
        val key: String?,
        val fields: Map<String, String>,
        val options: RedisStreamPublishOptions,
    )

    private class RecordingPublisher(private val fail: Boolean = false) : RedisStreamPublisher {
        val requests = mutableListOf<PublishRequest>()

        override fun publish(
            partitionKey: String?,
            fields: Map<String, String>,
            options: RedisStreamPublishOptions,
        ): PublishedRedisStreamMessage {
            if (fail) throw IllegalStateException("publish failed")
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
