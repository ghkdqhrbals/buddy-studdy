package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamClaimBatch
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamConsumerOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class RedisStreamAnnotationManagerLifecycleTest {
    @Test
    fun `workers start only in the lifecycle phase and stop with the context`() {
        val streams = CountingStreamOperations()
        val beanFactory =
            StaticListableBeanFactory(
                mapOf("sampleStreamListener" to SampleStreamListener()),
            )
        val manager =
            RedisStreamAnnotationManager(
                streams = streams,
                dispatcher = mock(RedisStreamMessageDispatcher::class.java),
                environment = MockEnvironment(),
                beanFactory = beanFactory,
            )

        manager.afterSingletonsInstantiated()
        Thread.sleep(30)

        assertThat(manager.isRunning).isFalse()
        assertThat(streams.readCount.get()).isZero()
        assertThat(manager.phase).isEqualTo(Int.MAX_VALUE)

        manager.start()
        waitUntil { streams.readCount.get() > 0 }

        assertThat(manager.isRunning).isTrue()

        manager.stop()
        val readsAfterStop = streams.readCount.get()
        Thread.sleep(30)

        assertThat(manager.isRunning).isFalse()
        assertThat(streams.readCount.get()).isEqualTo(readsAfterStop)
    }

    private fun waitUntil(assertion: () -> Boolean) {
        repeat(50) {
            if (assertion()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for Redis Stream worker.")
    }

    private data class SamplePayload(val value: String)

    private class SampleStreamListener {
        @Suppress("unused")
        @StreamListener(
            topic = RedisStreamTopic.DOMAIN_EVENTS,
            group = "test-group",
            consumer = "test-consumer",
            eventType = "TEST",
            payloadType = SamplePayload::class,
            blockTimeMs = 10,
            pollDelayMs = 10,
        )
        private suspend fun consume(payload: SamplePayload) = Unit
    }

    private class CountingStreamOperations : RedisStreamConsumerOperations {
        val readCount = AtomicInteger()

        override suspend fun acknowledge(message: RedisStreamMessage, group: String) = Unit
        override suspend fun acknowledgeAndDelete(message: RedisStreamMessage, group: String) = Unit

        override suspend fun readNew(
            topic: RedisStreamTopic,
            group: String,
            consumer: String,
            count: Long,
            timeout: Duration,
        ): List<RedisStreamMessage> {
            readCount.incrementAndGet()
            return emptyList()
        }

        override suspend fun autoClaim(
            topic: RedisStreamTopic,
            group: String,
            consumer: String,
            minIdleTime: Duration,
            count: Long,
            startId: String,
        ): RedisStreamClaimBatch = RedisStreamClaimBatch(startId, emptyList())
    }
}
