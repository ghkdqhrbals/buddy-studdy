package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import com.buddystudy.backend.config.BuddyStudyProperties
import io.lettuce.core.RedisFuture
import io.lettuce.core.StreamMessage
import io.lettuce.core.XAutoClaimArgs
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import io.lettuce.core.models.stream.ClaimedMessages
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.any
import org.mockito.Mockito.`when`
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate

class RedisStreamAutoClaimTest {
    @Test
    fun `idle messages are autoclaimed through Lettuce and returned as stream messages`(): Unit = runBlocking {
        val blockingRedis = mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val streamOperations = mock(StreamOperations::class.java) as StreamOperations<String, String, String>
        val factory = mock(RedisConnectionFactory::class.java)
        val connection = mock(RedisConnection::class.java)
        @Suppress("UNCHECKED_CAST")
        val commands = mock(RedisClusterAsyncCommands::class.java) as RedisClusterAsyncCommands<ByteArray, ByteArray>
        @Suppress("UNCHECKED_CAST")
        val future = mock(RedisFuture::class.java) as RedisFuture<ClaimedMessages<ByteArray, ByteArray>>
        val claimed = ClaimedMessages(
            "0-0",
            listOf(
                StreamMessage(
                    bytes("notification.question-push.requested.v1"),
                    "17-0",
                    mapOf(
                        bytes("eventType") to bytes("QUESTION_PUSH_REQUESTED"),
                        bytes("payload") to bytes("""{"recordId":17}"""),
                    ),
                ),
            ),
        )
        `when`(blockingRedis.opsForStream<String, String>()).thenReturn(streamOperations)
        `when`(blockingRedis.connectionFactory).thenReturn(factory)
        `when`(factory.connection).thenReturn(connection)
        `when`(connection.nativeConnection).thenReturn(commands)
        `when`(
            commands.xautoclaim(
                any(ByteArray::class.java),
                any<XAutoClaimArgs<ByteArray>>(),
            ),
        ).thenReturn(future)
        `when`(future.get()).thenReturn(claimed)
        val manager = RedisStreamTopicManager(
            redis = mock(ReactiveStringRedisTemplate::class.java),
            blockingRedis = blockingRedis,
            redactor = mock(SensitiveDataRedactor::class.java),
            properties = BuddyStudyProperties(),
        )

        val result = manager.autoClaim(
            topic = RedisStreamTopic.NOTIFICATION_QUESTION_PUSH_REQUESTED,
            group = "push",
            consumer = "push-recovery",
            minIdleTime = Duration.ofMinutes(5),
            count = 50,
            startId = "0-0",
        )

        assertThat(result.nextStartId).isEqualTo("0-0")
        assertThat(result.messages).containsExactly(
            RedisStreamMessage(
                streamKey = "notification.question-push.requested.v1",
                recordId = "17-0",
                fields = mapOf(
                    "eventType" to "QUESTION_PUSH_REQUESTED",
                    "payload" to """{"recordId":17}""",
                ),
            ),
        )
    }

    private fun bytes(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
}
