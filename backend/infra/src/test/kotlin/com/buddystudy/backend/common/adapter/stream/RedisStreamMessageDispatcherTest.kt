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
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class RedisStreamMessageDispatcherTest {
    @Test
    fun `successful typed handler is acknowledged after invocation`(): Unit = runBlocking {
        val streams = RecordingConsumerOperations()
        val failures = RecordingFailureHistory()
        val dispatcher = dispatcher(streams, failures)
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
        assertThat(failures.terminal).isEmpty()
        assertThat(failures.retryable).isEmpty()
    }

    @Test
    fun `Jackson conversion failure is recorded as terminal before acknowledging the poison message`() = runBlocking {
        val streams = RecordingConsumerOperations()
        val failures = RecordingFailureHistory()
        val dispatcher = dispatcher(streams, failures)

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

        assertThat(failures.terminal.single().javaClass.name)
            .contains("MismatchedInputException")
        assertThat(streams.acknowledged).containsExactly("sample-group" to "1-0")
    }

    @Test
    fun `handler failure leaves the claimed message pending for another recovery`() = runBlocking {
        val streams = RecordingConsumerOperations()
        val failures = RecordingFailureHistory()
        val dispatcher = dispatcher(streams, failures)

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
        assertThat(failures.retryable.single())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("handler failed")
    }

    @Test
    fun `non fatal linkage failure leaves the message pending without escaping the dispatcher`() = runBlocking {
        val streams = RecordingConsumerOperations()
        val failures = RecordingFailureHistory()
        val dispatcher = dispatcher(streams, failures)

        dispatcher.dispatch(
            bean = SampleHandler(),
            method = handlerMethod("linkageFail"),
            eventType = "SAMPLE",
            payloadType = SamplePayload::class.java,
            group = "sample-group",
            options = StreamOptions.ACK,
            message = message("""{"value":31}"""),
            claimed = false,
        )

        assertThat(streams.acknowledged).isEmpty()
        assertThat(failures.retryable.single()).isInstanceOf(NoClassDefFoundError::class.java)
    }

    @Test
    fun `handler failure is an error with complete root stack trace`(output: CapturedOutput) = runBlocking {
        val dispatcher = dispatcher(RecordingConsumerOperations())

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

        assertThat(output.out).contains("errorType=java.lang.IllegalStateException error=handler failed")
        assertThat(output.out).contains("ERROR")
        assertThat(output.out).doesNotContain("InvocationTargetException")
        assertThat(output.out).contains("\tat ")
    }

    @Test
    fun `ack del option acknowledges and deletes after successful invocation`() = runBlocking {
        val streams = RecordingConsumerOperations()
        val dispatcher = dispatcher(streams)

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
        val dispatcher = dispatcher(streams)

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

    @Test
    fun `unexpected event type is acknowledged without invoking the typed handler`() = runBlocking {
        val streams = RecordingConsumerOperations()
        val failures = RecordingFailureHistory()
        val dispatcher = dispatcher(streams, failures)
        val handler = SampleHandler()

        dispatcher.dispatch(
            bean = handler,
            method = handlerMethod("consume"),
            eventType = "EXPECTED",
            payloadType = SamplePayload::class.java,
            group = "sample-group",
            options = StreamOptions.ACK,
            message = message("""{"value":31}"""),
            claimed = false,
        )

        assertThat(handler.values).isEmpty()
        assertThat(failures.terminal.single())
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Expected eventType 'EXPECTED'")
        assertThat(streams.acknowledged).containsExactly("sample-group" to "1-0")
    }

    private fun dispatcher(
        streams: RedisStreamConsumerOperations,
        failures: RedisStreamFailureHistory = RecordingFailureHistory(),
    ) = RedisStreamMessageDispatcher(
        streams,
        JacksonRedisStreamCodec(JsonMapperProvider.mapper),
        failures,
    )

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

        @Suppress("unused", "UNUSED_PARAMETER")
        private suspend fun linkageFail(payload: SamplePayload, context: StreamMessageContext) {
            throw NoClassDefFoundError("stream handler dependency")
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

    private class RecordingFailureHistory : RedisStreamFailureHistory {
        val terminal = mutableListOf<Throwable>()
        val retryable = mutableListOf<Throwable>()

        override suspend fun recordTerminal(
            message: RedisStreamMessage,
            consumerGroup: String,
            error: Throwable,
        ): Boolean {
            terminal += error
            return true
        }

        override suspend fun recordRetryable(
            message: RedisStreamMessage,
            consumerGroup: String,
            error: Throwable,
        ): Boolean {
            retryable += error
            return true
        }
    }
}
