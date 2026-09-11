package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.externalapi.adapter.outbound.usage.OpenAIUsageRecorder
import com.fasterxml.jackson.databind.JsonNode

/** Accounting observes the provider stream before UI filtering, including interrupted responses. */
internal class VoiceTutorRealtimeUsageTracker(
    private val callId: String,
    private val model: String,
    private val transcriptionModel: String,
    private val nanoTime: () -> Long = System::nanoTime,
    private val emit: (VoiceTutorRealtimeUsage) -> Unit = ::recordVoiceTutorRealtimeUsage,
) {
    private data class Pending(val startedAt: Long, val stage: String)
    private val pending = linkedMapOf<String, Pending>()
    private val completed = linkedSetOf<String>()
    private val transcriptions = linkedSetOf<String>()
    private var closed = false

    @Synchronized
    fun observe(raw: String) {
        if (closed) return
        val event = runCatching { JsonMapperProvider.mapper.readTree(raw) }.getOrNull() ?: return
        val body = event.path("response")
        when (event.path("type").asText()) {
            "response.created" -> {
                val id = identity(body.path("id")) ?: return
                if (id !in completed) pending.putIfAbsent(id, Pending(nanoTime(), stage(body)))
                // Real sessions have at most one active response. Keep malformed streams bounded.
                while (pending.size > MAX_PENDING) {
                    val oldest = pending.keys.first()
                    finish(oldest, "disconnected", null)
                }
            }
            "response.done" -> {
                val id = identity(body.path("id")) ?: return
                val outcome = when (body.path("status").asText()) {
                    "completed" -> "succeeded"
                    "cancelled" -> "cancelled"
                    "failed" -> "failed"
                    "incomplete" -> "incomplete"
                    else -> "unknown"
                }
                finish(id, outcome, body.path("usage"), stage(body))
            }
            "conversation.item.input_audio_transcription.completed",
            "conversation.item.input_audio_transcription.failed" -> {
                val id = identity(event.path("item_id")) ?: return
                val index = event.path("content_index").takeIf { it.isIntegralNumber && it.canConvertToInt() }
                    ?.intValue()?.takeIf { it >= 0 } ?: return
                if (!transcriptions.add("$id:$index")) return
                trim(transcriptions)
                publish(VoiceTutorRealtimeUsage(
                    stage = "transcription", model = transcriptionModel,
                    outcome = if (event.path("type").asText().endsWith(".completed")) "succeeded" else "failed",
                    usage = event.path("usage"), durationMs = null,
                    eventRef = voiceTutorCallReference("$callId:transcription:$id:$index"),
                ))
            }
        }
    }

    /** A disconnect is an unknown bill, never a claim that the response cost zero tokens. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        pending.keys.toList().forEach { finish(it, "disconnected", null) }
        pending.clear()
        completed.clear()
        transcriptions.clear()
    }

    private fun finish(id: String, outcome: String, usage: JsonNode?, fallbackStage: String = "response") {
        if (!completed.add(id)) return
        trim(completed)
        val active = pending.remove(id)
        publish(VoiceTutorRealtimeUsage(
            stage = active?.stage ?: fallbackStage, model = model, outcome = outcome, usage = usage,
            durationMs = active?.let { ((nanoTime() - it.startedAt) / 1_000_000).coerceAtLeast(0) },
            eventRef = voiceTutorCallReference("$callId:response:$id"),
        ))
    }

    private fun publish(value: VoiceTutorRealtimeUsage) {
        // Accounting must never end audio, suppress a transcript or cause a model retry.
        runCatching { emit(value) }
    }

    private fun stage(response: JsonNode): String = when (
        response.path("metadata").path("buddystudy_usage_operation").asText()
    ) {
        "voice-opening" -> "opening"
        "voice-question-readback" -> "question-readback"
        "voice-mutation-confirmation" -> "mutation-confirmation"
        "voice-learning-notice" -> "learning-notice"
        "voice-cancellation-notice" -> "cancellation-notice"
        "voice-goodbye" -> "goodbye"
        "voice-quota-notice" -> "quota-notice"
        else -> "response"
    }

    private fun identity(value: JsonNode): String? = value.takeIf { it.isTextual }?.textValue()
        ?.takeIf { it.length in 1..256 }

    private fun trim(ids: LinkedHashSet<String>) {
        while (ids.size > MAX_COMPLETED) ids.remove(ids.first())
    }

    private companion object {
        const val MAX_PENDING = 32
        const val MAX_COMPLETED = 8192
    }
}

internal data class VoiceTutorRealtimeUsage(
    val stage: String,
    val model: String,
    val outcome: String,
    val usage: JsonNode?,
    val durationMs: Long?,
    val eventRef: String,
)

private fun recordVoiceTutorRealtimeUsage(value: VoiceTutorRealtimeUsage) {
    OpenAIUsageRecorder().record(
        operation = "realtime", stage = value.stage, model = value.model, outcome = value.outcome,
        usageJson = value.usage, durationMs = value.durationMs,
        transport = "realtime", granularity = "provider_response", eventRef = value.eventRef,
    )
}
