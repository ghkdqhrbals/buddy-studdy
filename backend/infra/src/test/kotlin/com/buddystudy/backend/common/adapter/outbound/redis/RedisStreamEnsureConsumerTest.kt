package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import com.buddystudy.backend.config.BuddyStudyProperties
import io.lettuce.core.Consumer
import io.lettuce.core.RedisFuture
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate

class RedisStreamEnsureConsumerTest {
    @Test
    fun `recovery consumer and missing group are created explicitly at startup`(): Unit = runBlocking {
        val blockingRedis = mock(StringRedisTemplate::class.java)
        val factory = mock(RedisConnectionFactory::class.java)
        val connection = mock(RedisConnection::class.java)
        @Suppress("UNCHECKED_CAST")
        val commands = mock(RedisClusterAsyncCommands::class.java) as RedisClusterAsyncCommands<ByteArray, ByteArray>
        @Suppress("UNCHECKED_CAST")
        val groupFuture = mock(RedisFuture::class.java) as RedisFuture<String>
        @Suppress("UNCHECKED_CAST")
        val consumerFuture = mock(RedisFuture::class.java) as RedisFuture<Boolean>
        `when`(blockingRedis.connectionFactory).thenReturn(factory)
        `when`(factory.connection).thenReturn(connection)
        `when`(connection.nativeConnection).thenReturn(commands)
        `when`(
            commands.xgroupCreate(
                any<XReadArgs.StreamOffset<ByteArray>>(),
                any(ByteArray::class.java),
                any(XGroupCreateArgs::class.java),
            ),
        ).thenReturn(groupFuture)
        `when`(groupFuture.get()).thenReturn("OK")
        `when`(
            commands.xgroupCreateconsumer(
                any(ByteArray::class.java),
                any<Consumer<ByteArray>>(),
            ),
        ).thenReturn(consumerFuture)
        `when`(consumerFuture.get()).thenReturn(true)
        val manager = RedisStreamTopicManager(
            redis = mock(ReactiveStringRedisTemplate::class.java),
            blockingRedis = blockingRedis,
            redactor = mock(SensitiveDataRedactor::class.java),
            properties = BuddyStudyProperties(),
        )

        manager.ensureConsumer(
            topic = RedisStreamTopic.STUDY_ANSWER_GRADING_REQUESTED,
            group = "bs-backend-answer-grading",
            consumer = "buddystudy-answer-grading-recovery",
        )

        verify(commands).xgroupCreate(
            any<XReadArgs.StreamOffset<ByteArray>>(),
            any(ByteArray::class.java),
            any(XGroupCreateArgs::class.java),
        )
        verify(commands).xgroupCreateconsumer(
            any(ByteArray::class.java),
            any<Consumer<ByteArray>>(),
        )
    }
}
