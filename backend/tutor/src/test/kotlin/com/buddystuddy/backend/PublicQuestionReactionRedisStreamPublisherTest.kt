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
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.util.stream.Stream

@ExtendWith(OutputCaptureExtension::class)
class PublicQuestionReactionRedisStreamPublisherTest {
    @Test
    fun `publish methods return false when streams are disabled`() {
        val service = service(enabled = false, viewPublisher = RecordingPublisher(), actionPublisher = RecordingPublisher())

        assertThat(service.publishViewed(1, 2)).isFalse()
        assertThat(service.publishLiked(1, 2)).isFalse()
    }

    @Test
    fun `enabled streams require publisher beans`() {
        assertThatThrownBy { service(enabled = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("viewStreamPublisher bean is required")

        assertThatThrownBy { service(enabled = true, viewPublisher = RecordingPublisher()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("actionStreamPublisher bean is required")
    }

    @Test
    fun `view and action events publish typed field maps`(output: CapturedOutput) {
        val viewPublisher = RecordingPublisher()
        val actionPublisher = RecordingPublisher()
        val service = service(enabled = true, viewPublisher = viewPublisher, actionPublisher = actionPublisher)

        assertThat(service.publishViewed(20, null)).isTrue()
        assertThat(service.publishLiked(30, 40)).isTrue()
        assertThat(service.publishUnliked(31, 41)).isTrue()

        val viewRequest = viewPublisher.requests.single()
        assertThat(viewRequest.key).isEqualTo("20")
        assertThat(viewRequest.fields).containsEntry("eventType", "CONTENT_VIEWED")
        assertThat(viewRequest.fields).containsEntry("questionId", "20")
        assertThat(viewRequest.fields).doesNotContainKey("userId")
        assertThat(actionPublisher.requests).hasSize(2)
        assertThat(actionPublisher.requests[0].fields).containsEntry("eventType", "QUESTION_LIKED")
        assertThat(actionPublisher.requests[0].fields).containsEntry("userId", "40")
        assertThat(actionPublisher.requests[1].fields).containsEntry("eventType", "QUESTION_UNLIKED")
        assertThat(output.out)
            .contains("redis_stream_publish_started")
            .contains("redis_stream_publish_succeeded")
            .contains("eventType=CONTENT_VIEWED")
            .contains("eventType=QUESTION_LIKED")
    }

    @Test
    fun `reaction publishers emit distinct stats action event types`() {
        val actionPublisher = RecordingPublisher()
        val service = service(enabled = true, viewPublisher = RecordingPublisher(), actionPublisher = actionPublisher)

        assertThat(service.publishLiked(41, 100)).isTrue()
        assertThat(service.publishUnliked(41, 100)).isTrue()

        assertThat(actionPublisher.requests.map { it.key }).containsExactly("41", "41")
        assertThat(actionPublisher.requests.map { it.fields["eventType"] })
            .containsExactly("QUESTION_LIKED", "QUESTION_UNLIKED")
        assertThat(actionPublisher.requests)
            .allSatisfy { request ->
                assertThat(request.fields).containsEntry("questionId", "41")
                assertThat(request.fields).containsEntry("userId", "100")
                assertThat(request.fields).containsKeys("createdAt", "eventId")
                assertThat(request.options.maxLen).isEqualTo(100_000)
                assertThat(request.options.approximateTrimming).isTrue()
            }
    }

    @Test
    fun `publish methods return false when publisher throws`() {
        val failing = RecordingPublisher(fail = true)
        val service = service(enabled = true, viewPublisher = failing, actionPublisher = failing)

        assertThat(service.publishViewed(1, 2)).isFalse()
        assertThat(service.publishLiked(1, 2)).isFalse()
    }

    @Test
    fun `string action publisher rejects unknown event type`() {
        val service = service(enabled = true, viewPublisher = RecordingPublisher(), actionPublisher = RecordingPublisher())

        assertThatThrownBy { service.publishAction(1, "UNKNOWN", 2) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun service(
        enabled: Boolean,
        viewPublisher: RedisStreamPublisher? = null,
        actionPublisher: RedisStreamPublisher? = null,
    ): PublicQuestionReactionRedisStreamPublisher {
        val properties = BuddyStuddyProperties().apply {
            streams.enabled = enabled
        }
        return PublicQuestionReactionRedisStreamPublisher(
            properties,
            provider(viewPublisher),
            provider(actionPublisher),
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
