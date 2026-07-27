package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import com.buddystudy.backend.config.BuddyStudyProperties
import org.assertj.core.api.Assertions.assertThat
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
                    key = "domain-stream",
                    questionGeneratedKey = "question-generated-stream",
                    pushKey = "push-stream",
                    domainMaxLen = 2_000,
                    questionGeneratedMaxLen = 4_000,
                    pushMaxLen = 8_000,
                ),
            ),
        )

        assertThat(manager.definition(RedisStreamTopic.DOMAIN_EVENTS))
            .isEqualTo(
                RedisStreamTopicDefinition(
                    topic = RedisStreamTopic.DOMAIN_EVENTS,
                    streamKey = "domain-stream",
                    maxLength = 2_000,
                ),
            )
        assertThat(manager.definition(RedisStreamTopic.QUESTION_GENERATED))
            .isEqualTo(
                RedisStreamTopicDefinition(
                    topic = RedisStreamTopic.QUESTION_GENERATED,
                    streamKey = "question-generated-stream",
                    maxLength = 4_000,
                ),
            )
        assertThat(manager.definition(RedisStreamTopic.PUSH_EVENTS))
            .isEqualTo(
                RedisStreamTopicDefinition(
                    topic = RedisStreamTopic.PUSH_EVENTS,
                    streamKey = "push-stream",
                    maxLength = 8_000,
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
                    domainMaxLen = 0,
                    questionGeneratedMaxLen = -5,
                    pushMaxLen = -10,
                ),
            ),
        )

        assertThat(manager.definition(RedisStreamTopic.DOMAIN_EVENTS).maxLength).isEqualTo(1)
        assertThat(manager.definition(RedisStreamTopic.QUESTION_GENERATED).maxLength).isEqualTo(1)
        assertThat(manager.definition(RedisStreamTopic.PUSH_EVENTS).maxLength).isEqualTo(1)
    }
}
