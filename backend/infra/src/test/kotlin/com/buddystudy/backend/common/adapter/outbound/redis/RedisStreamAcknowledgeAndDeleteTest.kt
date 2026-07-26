package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import com.buddystudy.backend.config.BuddyStudyProperties
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux

class RedisStreamAcknowledgeAndDeleteTest {
    @Test
    fun `acknowledge and delete execute as one redis script request`(): Unit = runBlocking {
        val redis = mock(ReactiveStringRedisTemplate::class.java)
        `when`(
            redis.execute<Boolean>(
                ArgumentMatchers.any<RedisScript<Boolean>>(),
                eq(listOf("buddystudy-events-v1")),
                eq(listOf("push", "17-0")),
            ),
        ).thenReturn(Flux.just(true))
        val manager = RedisStreamTopicManager(
            redis = redis,
            blockingRedis = mock(StringRedisTemplate::class.java),
            redactor = mock(SensitiveDataRedactor::class.java),
            properties = BuddyStudyProperties(),
        )

        manager.acknowledgeAndDelete(
            message = RedisStreamMessage(
                streamKey = "buddystudy-events-v1",
                recordId = "17-0",
                fields = emptyMap(),
            ),
            group = "push",
        )

        @Suppress("UNCHECKED_CAST")
        val scriptCaptor = ArgumentCaptor.forClass(RedisScript::class.java) as ArgumentCaptor<RedisScript<Boolean>>
        verify(redis, times(1)).execute(
            scriptCaptor.capture(),
            eq(listOf("buddystudy-events-v1")),
            eq(listOf("push", "17-0")),
        )
        verifyNoMoreInteractions(redis)
        assertThat(scriptCaptor.value.scriptAsString)
            .contains("redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])")
            .contains("redis.call('XDEL', KEYS[1], ARGV[2])")
    }
}
