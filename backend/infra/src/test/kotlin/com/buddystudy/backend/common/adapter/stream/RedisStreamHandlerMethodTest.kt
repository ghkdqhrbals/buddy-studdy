package com.buddystudy.backend.common.adapter.stream

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

class RedisStreamHandlerMethodTest {
    @Test
    fun `private suspend handler receives the Jackson object and stream context`(): Unit = runBlocking {
        val bean = SampleHandler()
        val method = SampleHandler::class.java.getDeclaredMethod(
            "consume",
            SamplePayload::class.java,
            StreamMessageContext::class.java,
            kotlin.coroutines.Continuation::class.java,
        )
        val handler = RedisStreamHandlerMethod.create("sampleHandler", method, SamplePayload::class.java)
        val context = context()

        handler.invoke(bean, SamplePayload(31), context)

        assertThat(bean.received).isEqualTo(SamplePayload(31) to context)
    }

    @Test
    fun `handler declaration rejects an object type that its parameter cannot accept`() {
        val method = SampleHandler::class.java.getDeclaredMethod(
            "consume",
            SamplePayload::class.java,
            StreamMessageContext::class.java,
            kotlin.coroutines.Continuation::class.java,
        )

        assertThatThrownBy {
            RedisStreamHandlerMethod.create("sampleHandler", method, String::class.java)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not accept")
    }

    @Test
    fun `handler invocation exposes the original exception instead of reflection wrapper`() {
        val method = SampleHandler::class.java.getDeclaredMethod(
            "fail",
            SamplePayload::class.java,
            StreamMessageContext::class.java,
            kotlin.coroutines.Continuation::class.java,
        )
        val handler = RedisStreamHandlerMethod.create("sampleHandler", method, SamplePayload::class.java)

        val error = catchThrowable {
            runBlocking {
                handler.invoke(SampleHandler(), SamplePayload(31), context())
            }
        }

        assertThat(error)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("handler failed")
    }

    private fun context() = StreamMessageContext(
        streamKey = "events",
        recordId = "1-0",
        eventId = "event-1",
        eventType = "SAMPLE",
        fields = mapOf("payload" to """{"value":31}"""),
        claimed = false,
    )

    private data class SamplePayload(val value: Int)

    private class SampleHandler {
        var received: Pair<SamplePayload, StreamMessageContext>? = null

        @Suppress("unused")
        private suspend fun consume(payload: SamplePayload, context: StreamMessageContext) {
            received = payload to context
        }

        @Suppress("unused", "UNUSED_PARAMETER")
        private suspend fun fail(payload: SamplePayload, context: StreamMessageContext) {
            throw IllegalStateException("handler failed")
        }
    }
}
