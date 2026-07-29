package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishedMessage
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.community.adapter.outbound.stream.PublicQuestionReactionRedisStreamPublisher
import com.buddystudy.backend.config.BuddyStudyProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PublicQuestionReactionRedisStreamPublisherTest {
    @Test
    fun `publish view returns false when streams are disabled`(): Unit = runBlocking {
        val publisher = RecordingPublisher()
        val service = service(enabled = false, publisher = publisher)

        assertThat(service.publishViewed(1, 2)).isFalse()
        assertThat(publisher.requests).isEmpty()
    }

    @Test
    fun `view event publishes typed field map`(): Unit = runBlocking {
        val publisher = RecordingPublisher()
        val service = service(enabled = true, publisher = publisher)

        assertThat(service.publishViewed(20, null)).isTrue()

        val request = publisher.requests.single()
        assertThat(request.topic).isEqualTo(RedisStreamTopic.COMMUNITY_QUESTION_VIEWED)
        assertThat(request.fields).containsEntry("eventType", "CONTENT_VIEWED")
        assertThat(request.fields).containsEntry("questionId", "20")
        assertThat(request.fields).doesNotContainKey("userId")
    }

    @Test
    fun `publish view returns false when publisher throws`(): Unit = runBlocking {
        val service = service(enabled = true, publisher = RecordingPublisher(fail = true))

        assertThat(service.publishViewed(1, 2)).isFalse()
    }

    private fun service(enabled: Boolean, publisher: RedisStreamPublishOperations): PublicQuestionReactionRedisStreamPublisher {
        val properties = BuddyStudyProperties().apply {
            streams.enabled = enabled
            streams.questionViewedKey = "community.question.viewed.v1"
        }
        return PublicQuestionReactionRedisStreamPublisher(properties, publisher)
    }

    private data class PublishRequest(val topic: RedisStreamTopic, val fields: Map<String, String>)

    private class RecordingPublisher(private val fail: Boolean = false) : RedisStreamPublishOperations {
        val requests = mutableListOf<PublishRequest>()

        override suspend fun publish(topic: RedisStreamTopic, fields: Map<String, String>): RedisStreamPublishedMessage {
            if (fail) throw IllegalStateException("publish failed")
            requests += PublishRequest(topic, fields)
            return RedisStreamPublishedMessage(topic.apiName, "record-1")
        }
    }
}
