package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import kotlinx.coroutines.delay
import java.time.Duration

/** Only the explicit pre-write persistence gate is safe to retry automatically. */
internal object VoiceTutorPersistenceRetry {
    private val intervalsMillis = listOf(100L, 200L, 400L, 800L, 1_000L)
    private val budgetNanos = Duration.ofSeconds(3).toNanos()

    suspend fun execute(
        retryPending: Boolean,
        isCurrent: () -> Boolean,
        nanoTime: () -> Long = System::nanoTime,
        pause: suspend (Long) -> Unit = { delay(it) },
        operation: suspend () -> VoiceTutorMcpToolResult,
    ): VoiceTutorMcpToolResult {
        if (!isCurrent()) return superseded()
        var result = operation()
        if (!retryPending) return result
        val startedAt = nanoTime()
        for (interval in intervalsMillis) {
            if (!isPending(result)) return result
            if (!isCurrent()) return superseded()
            val remaining = budgetNanos - (nanoTime() - startedAt)
            val delayNanos = Duration.ofMillis(interval).toNanos()
            if (remaining <= delayNanos) return result
            pause(interval)
            // Speech, cancellation or focus changes can arrive during backoff.
            // Never execute once more on the authority checked before sleeping.
            if (!isCurrent()) return superseded()
            if (nanoTime() - startedAt >= budgetNanos) return result
            result = operation()
        }
        return result
    }

    private fun isPending(result: VoiceTutorMcpToolResult): Boolean = result.isError &&
        runCatching {
            JsonMapperProvider.mapper.readTree(result.output).path("error").path("code").asText() ==
                "INPUT_PERSISTENCE_PENDING"
        }.getOrDefault(false)

    private fun superseded() = nativeToolError("STALE_TURN",
        "Only this follow-up was superseded by newer speech. Previously accepted answers remain saved and grading continues. Listen to the latest turn; do not describe this as a failed answer submission.")
}
