package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class VoiceTutorPersistenceRetryTest {
    private val pending = nativeToolError("INPUT_PERSISTENCE_PENDING", "synthetic pre-write gate")

    @Test
    fun `persistent source delay has a finite request budget with increasing spacing`() = runBlocking<Unit> {
        var calls = 0
        var time = 0L
        val waits = mutableListOf<Long>()
        val result = VoiceTutorPersistenceRetry.execute(true, { true }, { time }, {
            waits += it
            time += Duration.ofMillis(it).toNanos()
        }) { calls++; pending }
        assertThat(result).isSameAs(pending)
        assertThat(calls).isEqualTo(6)
        assertThat(waits).containsExactly(100, 200, 400, 800, 1_000)
        assertThat(time).isLessThan(Duration.ofSeconds(3).toNanos())
    }

    @Test
    fun `new speech during backoff prevents another write attempt`() = runBlocking<Unit> {
        var current = true
        var calls = 0
        val result = VoiceTutorPersistenceRetry.execute(true, { current }, pause = { current = false }) {
            calls++; pending
        }
        assertThat(calls).isEqualTo(1)
        assertThat(result.output).contains("STALE_TURN")
    }

    @Test
    fun `already abandoned work never executes`() = runBlocking<Unit> {
        var calls = 0
        val result = VoiceTutorPersistenceRetry.execute(true, { false }) { calls++; pending }
        assertThat(calls).isZero()
        assertThat(result.output).contains("STALE_TURN")
    }

    @Test
    fun `late timer wake does not exceed the elapsed retry window`() = runBlocking<Unit> {
        var calls = 0
        var time = 0L
        val result = VoiceTutorPersistenceRetry.execute(true, { true }, { time }, {
            time += Duration.ofSeconds(4).toNanos()
        }) { calls++; pending }
        assertThat(calls).isEqualTo(1)
        assertThat(result).isSameAs(pending)
    }

    @Test
    fun `slow persistence requests also consume the elapsed retry window`() = runBlocking<Unit> {
        var calls = 0
        var time = 0L
        val result = VoiceTutorPersistenceRetry.execute(true, { true }, { time }, {
            time += Duration.ofMillis(it).toNanos()
        }) { calls++; time += Duration.ofSeconds(3).toNanos(); pending }
        assertThat(calls).isEqualTo(2)
        assertThat(result).isSameAs(pending)
    }

    @Test
    fun `success stops retrying and preserves the exact authoritative result`() = runBlocking<Unit> {
        var calls = 0
        val saved = VoiceTutorMcpToolResult("{\"saved\":true}", false)
        val result = VoiceTutorPersistenceRetry.execute(true, { true }, pause = {}) {
            if (++calls == 1) pending else saved
        }
        assertThat(calls).isEqualTo(2)
        assertThat(result).isSameAs(saved)
    }

    @Test
    fun `unknown timeouts malformed errors and reads are never automatically replayed`() = runBlocking<Unit> {
        val results = listOf(nativeToolError("TOOL_TIMEOUT", "unknown result"),
            nativeToolError("TOOL_UNAVAILABLE", "unknown result"),
            VoiceTutorMcpToolResult("malformed", true), VoiceTutorMcpToolResult("{}", true),
            pending.copy(isError = false))
        for (original in results) {
            var calls = 0
            val result = VoiceTutorPersistenceRetry.execute(true, { true }, pause = { error("Unexpected retry") }) {
                calls++; original
            }
            assertThat(calls).isEqualTo(1)
            assertThat(result).isSameAs(original)
        }
        var reads = 0
        val read = VoiceTutorPersistenceRetry.execute(false, { true }, pause = { error("Unexpected read retry") }) {
            reads++; pending
        }
        assertThat(reads).isEqualTo(1)
        assertThat(read).isSameAs(pending)
    }

    @Test
    fun `cancellation propagates without executing another attempt`() {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                VoiceTutorPersistenceRetry.execute(true, { true }, pause = { throw CancellationException("call ended") }) {
                    calls++; pending
                }
            }
        }
        assertThat(calls).isEqualTo(1)
    }
}
