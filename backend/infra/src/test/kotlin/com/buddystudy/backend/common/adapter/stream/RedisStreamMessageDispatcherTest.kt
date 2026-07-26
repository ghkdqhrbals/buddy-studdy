package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamClaimBatch
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamConsumerOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisStreamMessageDispatcherTest {
    @Test
    fun `successful typed handler is acknowledged after invocation`(): Unit = runBlocking {
        val streams = RecordingConsumerOperations()
        val dispatcher = RedisStreamMessageDispatcher(
            streams,
            JacksonRedisStreamCodec(JsonMapperProvider.mapper),
        )
        val handlerBean = SampleHandler()

        dispatcher.dispatch(
            bean = handlerBean,
            method = handlerMethod("consume"),
            eventType = "SAMPLE",
            payloadType = SamplePayload::class.java,
            group = "sample-group",
            options = StreamOptions.ACK,
            message = message("""{"value":31}"""),
            claimed = false,
        )

        assertThat(handlerBean.values).containsExactly(31)
        assertThat(streams.acknowledged).containsExactly("sample-group" to "1-0")
    }

    @Test
    fun `Jackson conversion failure leaves the message pending`() = runBlocking {
        val streams = RecordingConsumerOperations()
        val dispatcher = RedisStreamMessageDispatcher(
            streams,
            JacksonRedisStreamCodec(JsonMapperProvider.mapper),
        )

        dispatcher.dispatch(
            bean = SampleHandler(),
            method = handlerMethod("consume"),
            eventType = "SAMPLE",
            payloadType = SamplePayload::class.java,
            group = "sample-group",
            options = StreamOptions.ACK,
            message = message("""{"wrong":31}"""),
            claimed = false,
        )

        assertThat(streams.acknowledged).isEmpty()
    }

    @Test
    fun `handler failure leaves the claimed message pending for another recovery`() = runBlocking {
        val streams = RecordingConsumerOperations()
        val dispatcher = RedisStreamMessageDispatcher(
            streams,
            JacksonRedisStreamCodec(JsonMapperProvider.mapper),
        )

        dispatcher.dispatch(
            bean = SampleHandler(),
            method = handlerMethod("fail"),
            eventType = "SAMPLE",
            payloadType = SamplePayload::class.java,
            group = "sample-group",
            options = StreamOptions.ACK,
            message = message("""{"value":31}"""),
            claimed = true,
        )

        assertThat(streams.acknowledged).isEmpty()
    }

    @Test
    fun `ack del option acknowledges and deletes after successful invocation`() = runBlocking {
        val streams = RecordingConsumerOperations()
        val dispatcher = RedisStreamMessageDispatcher(
            streams,
            JacksonRedisStreamCodec(JsonMapperProvider.mapper),
        )

        dispatcher.dispatch(
            bean = SampleHandler(),
            method = handlerMethod("consume"),
            eventType = "SAMPLE",
            payloadType = SamplePayload::class.java,
            group = "sample-group",
            options = StreamOptions.ACK_DEL,
            message = message("""{"value":31}"""),
            claimed = false,
        )

        assertThat(streams.acknowledged).isEmpty()
        assertThat(streams.acknowledgedAndDeleted).containsExactly("sample-group" to "1-0")
    }

    @Test
    fun `none option leaves successful message pending`() = runBlocking {
        val streams = RecordingConsumerOperations()
        val dispatcher = RedisStreamMessageDispatcher(
            streams,
            JacksonRedisStreamCodec(JsonMapperProvider.mapper),
        )

        dispatcher.dispatch(
            bean = SampleHandler(),
            method = handlerMethod("consume"),
            eventType = "SAMPLE",
            payloadType = SamplePayload::class.java,
            group = "sample-group",
            options = StreamOptions.NONE,
            message = message("""{"value":31}"""),
            claimed = false,
        )

        assertThat(streams.acknowledged).isEmpty()
        assertThat(streams.acknowledgedAndDeleted).isEmpty()
    }

    private fun handlerMethod(name: String): RedisStreamHandlerMethod {
        val method = SampleHandler::class.java.getDeclaredMethod(
            name,
            SamplePayload::class.java,
            StreamMessageContext::class.java,
            kotlin.coroutines.Continuation::class.java,
        )
        return RedisStreamHandlerMethod.create("sampleHandler", method, SamplePayload::class.java)
    }

    private fun message(payload: String) = RedisStreamMessage(
        streamKey = "events",
        recordId = "1-0",
        fields = mapOf(
            "eventId" to "event-1",
            "eventType" to "SAMPLE",
            "payload" to payload,
        ),
    )

    private data class SamplePayload(val value: Int)

    private class SampleHandler {
        val values = mutableListOf<Int>()

        @Suppress("unused")
        private suspend fun consume(payload: SamplePayload, context: StreamMessageContext) {
            check(!context.claimed)
            values += payload.value
        }

        @Suppress("unused", "UNUSED_PARAMETER")
        private suspend fun fail(payload: SamplePayload, context: StreamMessageContext) {
            throw IllegalStateException("handler failed")
        }
    }

    private class RecordingConsumerOperations : RedisStreamConsumerOperations {
        val acknowledged = mutableListOf<Pair<String, String>>()
        val acknowledgedAndDeleted = mutableListOf<Pair<String, String>>()

        override suspend fun acknowledge(message: RedisStreamMessage, group: String) {
            acknowledged += group to message.recordId
        }

        override suspend fun acknowledgeAndDelete(message: RedisStreamMessage, group: String) {
            acknowledgedAndDeleted += group to message.recordId
        }

        override suspend fun readNew(
            topic: RedisStreamTopic,
            group: String,
            consumer: String,
            count: Long,
            timeout: Duration,
        ): List<RedisStreamMessage> = emptyList()

        override suspend fun autoClaim(
            topic: RedisStreamTopic,
            group: String,
            consumer: String,
            minIdleTime: Duration,
            count: Long,
            startId: String,
        ): RedisStreamClaimBatch = RedisStreamClaimBatch("0-0", emptyList())
    }
}
