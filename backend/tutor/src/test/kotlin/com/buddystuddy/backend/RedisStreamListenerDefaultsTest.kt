package com.buddystuddy.backend

import com.buddystuddy.backend.community.adapter.inbound.stream.QuestionStatsStreamListener
import com.buddystuddy.backend.study.adapter.inbound.stream.PushStreamListener
import com.redisstream.consumer.StreamListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisStreamListenerDefaultsTest {
    @Test
    fun `stream listeners use unified backend consumer group defaults`() {
        val listeners = listOf(
            streamListener(PushStreamListener::class.java, "onPushRequested"),
            streamListener(QuestionStatsStreamListener::class.java, "onQuestionViewed"),
        )

        assertThat(listeners.map { it.groupId }).allSatisfy {
            assertThat(it).contains("bs-backend")
        }
    }

    @Test
    fun `push and view listeners use requested default concurrency`() {
        assertThat(streamListener(PushStreamListener::class.java, "onPushRequested").concurrency)
            .contains("4")
        assertThat(streamListener(QuestionStatsStreamListener::class.java, "onQuestionViewed").concurrency)
            .contains("8")
    }

    @Test
    fun `push and view listeners use stream prefixes without bs namespace`() {
        assertThat(streamListener(PushStreamListener::class.java, "onPushRequested").streamPrefix)
            .contains("push-v1")
            .doesNotContain("bs-push-v1")
        assertThat(streamListener(QuestionStatsStreamListener::class.java, "onQuestionViewed").streamPrefix)
            .contains("view-v1")
            .doesNotContain("bs-view-content-v1")
    }

    private fun streamListener(type: Class<*>, methodName: String): StreamListener =
        type.methods.single { it.name == methodName }.getAnnotation(StreamListener::class.java)
}
