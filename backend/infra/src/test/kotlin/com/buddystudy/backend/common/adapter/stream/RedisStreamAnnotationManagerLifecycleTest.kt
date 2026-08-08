package com.buddystudy.backend.common.adapter.stream

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamClaimBatch
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamConsumerOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
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
        waitUntil { streams.readCount.get() > 0 && streams.ensuredConsumers.isNotEmpty() }

        assertThat(manager.isRunning).isTrue()
        assertThat(streams.ensuredConsumers)
            .containsExactly("test-group" to "test-consumer-recovery")

        manager.stop()
        val readsAfterStop = streams.readCount.get()
        Thread.sleep(30)

        assertThat(manager.isRunning).isFalse()
        assertThat(streams.readCount.get()).isEqualTo(readsAfterStop)
    }

    @Test
    fun `listener continues polling after a non fatal linkage error`() {
        val streams = FailingOnceStreamOperations()
        val manager = manager(streams)

        manager.afterSingletonsInstantiated()
        manager.start()
        waitUntil { streams.readCount.get() > 1 }

        assertThat(manager.isRunning).isTrue()
        manager.stop()
    }

    @Test
    fun `listener abandons a stalled blocking read and polls again`() {
        val streams = StallingOnceStreamOperations()
        val manager = manager(streams)

        manager.afterSingletonsInstantiated()
        manager.start()
        waitUntil { streams.readCount.get() > 1 }

        assertThat(manager.isRunning).isTrue()
        manager.stop()
    }

    @Test
    fun `listener exits when a cancelled read is wrapped as a Redis failure during shutdown`() {
        val streams = ShutdownInterruptedStreamOperations()
        val manager = manager(streams)
        val logger = LoggerFactory.getLogger(RedisStreamAnnotationManager::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            manager.afterSingletonsInstantiated()
            manager.start()
            waitUntil { streams.readCount.get() == 1 }

            manager.stop()
            Thread.sleep(30)

            assertThat(manager.isRunning).isFalse()
            assertThat(streams.readCount.get()).isEqualTo(1)
            assertThat(appender.list.map(ILoggingEvent::getFormattedMessage))
                .noneMatch { it.startsWith("redis_stream_listener_poll_failed") }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            manager.stop()
        }
    }

    private fun manager(streams: RedisStreamConsumerOperations): RedisStreamAnnotationManager =
        RedisStreamAnnotationManager(
            streams = streams,
            dispatcher = mock(RedisStreamMessageDispatcher::class.java),
            environment = MockEnvironment(),
            beanFactory = StaticListableBeanFactory(mapOf("sampleStreamListener" to SampleStreamListener())),
        )

    private fun waitUntil(assertion: () -> Boolean) {
        repeat(100) {
            if (assertion()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for Redis Stream worker.")
    }

    private data class SamplePayload(val value: String)

    private class SampleStreamListener {
        @Suppress("unused")
        @StreamListener(
            topic = RedisStreamTopic.NOTIFICATION_MESSAGE_REQUESTED,
            group = "test-group",
            consumer = "test-consumer",
            eventType = "TEST",
            payloadType = SamplePayload::class,
            blockTimeMs = 10,
            pollDelayMs = 10,
        )
        private suspend fun consume(payload: SamplePayload) = Unit

        @Suppress("unused")
        @StreamScheduler(
            topic = RedisStreamTopic.NOTIFICATION_MESSAGE_REQUESTED,
            group = "test-group",
            consumer = "test-consumer-recovery",
            eventType = "TEST",
            payloadType = SamplePayload::class,
            batchSize = 10,
            minIdleTimeMs = 10,
            fixedDelayMs = 10,
            initialDelayMs = 0,
        )
        private suspend fun recover(payload: SamplePayload) = Unit
    }

    private class CountingStreamOperations : RedisStreamConsumerOperations {
        val readCount = AtomicInteger()
        val ensuredConsumers = CopyOnWriteArrayList<Pair<String, String>>()

        override suspend fun acknowledge(message: RedisStreamMessage, group: String) = Unit
        override suspend fun acknowledgeAndDelete(message: RedisStreamMessage, group: String) = Unit
        override suspend fun ensureConsumer(topic: RedisStreamTopic, group: String, consumer: String) {
            ensuredConsumers += group to consumer
        }

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

    private class FailingOnceStreamOperations : RedisStreamConsumerOperations {
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
            if (readCount.incrementAndGet() == 1) {
                throw NoClassDefFoundError("transient stream dependency")
            }
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

    private class StallingOnceStreamOperations : RedisStreamConsumerOperations {
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
            if (readCount.incrementAndGet() == 1) {
                kotlinx.coroutines.delay(5_000)
            }
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

    private class ShutdownInterruptedStreamOperations : RedisStreamConsumerOperations {
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
            try {
                awaitCancellation()
            } catch (error: CancellationException) {
                throw IllegalStateException("Redis command interrupted", InterruptedException())
            }
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
