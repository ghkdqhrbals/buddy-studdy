package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import com.buddystudy.backend.config.BuddyStudyProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate

class RedisStreamTopicDefinitionTest {
    @Test
    fun `stream topics use independent keys and maximum lengths`() {
        val manager = RedisStreamTopicManager(
            redis = mock(ReactiveStringRedisTemplate::class.java),
            blockingRedis = mock(StringRedisTemplate::class.java),
            redactor = mock(SensitiveDataRedactor::class.java),
            properties = BuddyStudyProperties(
                streams = BuddyStudyProperties.Streams(
                    notificationRequestedKey = "notification.message.requested.v1",
                    questionGeneratedKey = "study.question.generated.v1",
                    questionPushRequestedKey = "notification.question-push.requested.v1",
                    questionCommentedKey = "community.question.commented.v1",
                    notificationRequestedMaxLen = 2_000,
                    questionGeneratedMaxLen = 4_000,
                    questionPushRequestedMaxLen = 8_000,
                    questionCommentedMaxLen = 16_000,
                ),
            ),
        )

        assertThat(manager.definition(RedisStreamTopic.NOTIFICATION_MESSAGE_REQUESTED))
            .isEqualTo(
                RedisStreamTopicDefinition(
                    topic = RedisStreamTopic.NOTIFICATION_MESSAGE_REQUESTED,
                    streamKey = "notification.message.requested.v1",
                    maxLength = 2_000,
                ),
            )
        assertThat(manager.definition(RedisStreamTopic.STUDY_QUESTION_GENERATED))
            .isEqualTo(
                RedisStreamTopicDefinition(
                    topic = RedisStreamTopic.STUDY_QUESTION_GENERATED,
                    streamKey = "study.question.generated.v1",
                    maxLength = 4_000,
                ),
            )
        assertThat(manager.definition(RedisStreamTopic.NOTIFICATION_QUESTION_PUSH_REQUESTED))
            .isEqualTo(
                RedisStreamTopicDefinition(
                    topic = RedisStreamTopic.NOTIFICATION_QUESTION_PUSH_REQUESTED,
                    streamKey = "notification.question-push.requested.v1",
                    maxLength = 8_000,
                ),
            )
        assertThat(manager.definition(RedisStreamTopic.COMMUNITY_QUESTION_COMMENTED))
            .isEqualTo(
                RedisStreamTopicDefinition(
                    topic = RedisStreamTopic.COMMUNITY_QUESTION_COMMENTED,
                    streamKey = "community.question.commented.v1",
                    maxLength = 16_000,
                ),
            )
    }

    @Test
    fun `each topic maximum length is clamped independently`() {
        val manager = RedisStreamTopicManager(
            redis = mock(ReactiveStringRedisTemplate::class.java),
            blockingRedis = mock(StringRedisTemplate::class.java),
            redactor = mock(SensitiveDataRedactor::class.java),
            properties = BuddyStudyProperties(
                streams = BuddyStudyProperties.Streams(
                    notificationRequestedMaxLen = 0,
                    questionGeneratedMaxLen = -5,
                    questionPushRequestedMaxLen = -10,
                ),
            ),
        )

        assertThat(manager.definition(RedisStreamTopic.NOTIFICATION_MESSAGE_REQUESTED).maxLength).isEqualTo(1)
        assertThat(manager.definition(RedisStreamTopic.STUDY_QUESTION_GENERATED).maxLength).isEqualTo(1)
        assertThat(manager.definition(RedisStreamTopic.NOTIFICATION_QUESTION_PUSH_REQUESTED).maxLength).isEqualTo(1)
    }

    @Test
    fun `active stream keys follow the four segment naming convention`() {
        val topics = RedisStreamTopic.entries

        assertThat(topics.map { it.apiName }).doesNotHaveDuplicates()
        assertThat(topics).allSatisfy { topic ->
            assertThat(topic.apiName.split('.')).hasSize(4)
            assertThat(topic.apiName).matches("[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.v[1-9][0-9]*")
        }
    }

    @Test
    fun `manager rejects invalid or duplicate active stream keys`() {
        assertThatThrownBy {
            RedisStreamTopicManager(
                redis = mock(ReactiveStringRedisTemplate::class.java),
                blockingRedis = mock(StringRedisTemplate::class.java),
                redactor = mock(SensitiveDataRedactor::class.java),
                properties = BuddyStudyProperties(
                    streams = BuddyStudyProperties.Streams(
                        notificationRequestedKey = "buddystudy-events-v1",
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("<business-domain>.<data-type>.<event-type>.<version>")

        assertThatThrownBy {
            RedisStreamTopicManager(
                redis = mock(ReactiveStringRedisTemplate::class.java),
                blockingRedis = mock(StringRedisTemplate::class.java),
                redactor = mock(SensitiveDataRedactor::class.java),
                properties = BuddyStudyProperties(
                    streams = BuddyStudyProperties.Streams(
                        notificationRequestedKey = "notification.message.requested.v1",
                        accountWithdrawnKey = "notification.message.requested.v1",
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Active Redis Stream keys must be unique.")
    }
}
