package com.buddystudy.backend.externalapi.adapter.outbound.usage

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Content-free PLG telemetry, independent of the private-content history exclusion. */
@Component
open class OpenAIUsageRecorder {
    fun record(
        operation: String,
        stage: String,
        model: String?,
        outcome: String,
        usageJson: JsonNode? = null,
        durationMs: Long? = null,
        attempt: Int? = null,
        maxRetries: Int? = null,
        httpStatus: Int? = null,
        transport: String = "rest",
        granularity: String = "logical",
        eventRef: String? = null,
    ) {
        runCatching {
            val event = JsonMapperProvider.mapper.createObjectNode()
                .put("event", "openai_usage")
                .put("schemaVersion", 1)
                .put("timestamp", Instant.now().toString())
                .put("eventRef", eventRef?.takeIf { EVENT_REF.matches(it) } ?: UUID.randomUUID().toString().replace("-", ""))
                .put("provider", "openai")
                .put("operation", operation.takeIf { it in OPERATIONS } ?: "other")
                .put("stage", stage.takeIf { it in STAGES } ?: "other")
                .put("model", model?.takeIf { MODEL.matches(it) && !it.startsWith("sk-") })
                .put("outcome", outcome.takeIf { it in OUTCOMES } ?: "other")
                .put("transport", transport.takeIf { it in TRANSPORTS } ?: "other")
                .put("granularity", granularity.takeIf { it in GRANULARITIES } ?: "other")
            event.putNullable("durationMs", durationMs?.takeIf { it >= 0 })
            event.putNullable("attempt", attempt?.takeIf { it > 0 }?.toLong())
            event.putNullable("retryCount", attempt?.takeIf { it > 0 }?.minus(1)?.toLong())
            event.putNullable("maxRetries", maxRetries?.takeIf { it >= 0 }?.toLong())
            event.putNullable("httpStatus", httpStatus?.takeIf { it in 100..599 }?.toLong())
            // Explicit numeric fields only: never serialize SDK metadata or a provider body.
            event.setAll<ObjectNode>(OpenAIUsageParser.parse(usageJson))
            emit(event)
        }.onFailure { runCatching { logger.warn("openai_usage_recording_failed") } }
    }

    protected open fun emit(event: ObjectNode) {
        logger.info("openai_usage {}", JsonMapperProvider.mapper.writeValueAsString(event))
    }

    private companion object {
        val logger = LoggerFactory.getLogger(OpenAIUsageRecorder::class.java)
        val EVENT_REF = Regex("[a-f0-9]{16,64}")
        val MODEL = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}")
        val OPERATIONS = setOf("question", "grading", "curriculum", "translation", "embedding", "validation", "voice_summary", "voice_input_assessment", "voice_preview", "realtime")
        val STAGES = setOf(
            "request", "rubric", "evidence", "critic", "judge", "adjudication", "coverage", "suggest_topics",
            "chunk", "reduce", "repair", "classify_evidence", "extract_result", "assess", "translate",
            "root_creation", "study_mutation", "mutation_confirmation", "spoken_question", "spoken_feedback",
            "response", "transcription", "opening", "question-readback", "mutation-confirmation", "learning-notice",
            "cancellation-notice", "goodbye", "quota-notice",
        )
        val OUTCOMES = setOf("succeeded", "failed", "cancelled", "incomplete", "disconnected")
        val TRANSPORTS = setOf("rest", "realtime")
        val GRANULARITIES = setOf("logical", "physical_attempt", "provider_response")
    }
}

/** Only explicit nonnegative provider counts survive; absent/malformed counts remain null. */
object OpenAIUsageParser {
    fun parse(usage: JsonNode?): ObjectNode {
        val input = usage?.get("input_token_details") ?: usage?.get("input_tokens_details") ?: usage?.get("prompt_tokens_details")
        val output = usage?.get("output_token_details") ?: usage?.get("output_tokens_details") ?: usage?.get("completion_tokens_details")
        val cached = input?.get("cached_tokens_details")
        val event = JsonMapperProvider.mapper.createObjectNode()
        val counts = linkedMapOf(
            "inputTokens" to count(usage, "input_tokens", "prompt_tokens"),
            "outputTokens" to count(usage, "output_tokens", "completion_tokens"),
            "totalTokens" to count(usage, "total_tokens"),
            "cachedInputTokens" to count(input, "cached_tokens"),
            "inputTextTokens" to count(input, "text_tokens"),
            "inputAudioTokens" to count(input, "audio_tokens"),
            "inputImageTokens" to count(input, "image_tokens"),
            "outputTextTokens" to count(output, "text_tokens"),
            "outputAudioTokens" to count(output, "audio_tokens"),
            "outputImageTokens" to count(output, "image_tokens"),
            "reasoningTokens" to count(output, "reasoning_tokens"),
            "cachedTextTokens" to count(cached, "text_tokens"),
            "cachedAudioTokens" to count(cached, "audio_tokens"),
            "cachedImageTokens" to count(cached, "image_tokens"),
            "acceptedPredictionTokens" to count(output, "accepted_prediction_tokens"),
            "rejectedPredictionTokens" to count(output, "rejected_prediction_tokens"),
        )
        counts.forEach { (name, value) -> event.putNullable(name, value) }
        val seconds = usage?.takeIf { it.path("type").asText() == "duration" }?.get("seconds")
            ?.takeIf { it.isNumber }?.doubleValue()?.takeIf { it.isFinite() && it >= 0 }
        if (seconds == null) event.putNull("audioSeconds") else event.put("audioSeconds", seconds)
        return event.put("usageAvailable", counts.values.any { it != null } || seconds != null)
    }

    private fun count(node: JsonNode?, vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        node?.get(key)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()?.takeIf { it >= 0 }
    }
}

internal fun ObjectNode.putNullable(name: String, value: Long?) {
    if (value == null) putNull(name) else put(name, value)
}
