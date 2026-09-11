package com.buddystudy.backend.externalapi.adapter.outbound.usage

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.study.application.openai.OpenAIRequestFailure
import com.fasterxml.jackson.databind.JsonNode
import com.openai.errors.OpenAIServiceException
import com.openai.models.completions.CompletionUsage
import com.openai.models.embeddings.CreateEmbeddingResponse
import kotlinx.coroutines.CancellationException
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer
import java.util.concurrent.atomic.AtomicInteger

/** One logical SDK request and its actual transport attempts; never changes retry policy. */
internal class OpenAISdkUsageCall(
    private val recorder: OpenAIUsageRecorder,
    private val operation: String,
    private val stage: String,
    private val model: String,
    private val maxRetries: Int?,
) {
    private val attempts = AtomicInteger()
    @Volatile private var lastHttpStatus: Int? = null

    val httpClientCustomizer = OpenAiHttpClientBuilderCustomizer { builder ->
        builder.interceptor { chain ->
            val attempt = attempts.incrementAndGet()
            lastHttpStatus = null
            val startedAt = System.nanoTime()
            var status: Int? = null
            var outcome = "failed"
            try {
                chain.proceed(chain.request()).also { response ->
                    status = response.code
                    lastHttpStatus = status
                    outcome = if (response.isSuccessful) "succeeded" else "failed"
                }
            } catch (failure: Throwable) {
                if (isOpenAICancellation(failure)) outcome = "cancelled"
                throw failure
            } finally {
                recorder.record(
                    operation, stage, model, outcome,
                    durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                    attempt = attempt, maxRetries = maxRetries, httpStatus = status,
                    granularity = "physical_attempt",
                )
            }
        }
    }

    fun <T> execute(usage: (T) -> Any?, call: () -> T): T {
        val startedAt = System.nanoTime()
        var usageJson: JsonNode? = null
        var outcome = "failed"
        try {
            return call().also { response ->
                usageJson = runCatching { openAISdkUsageJson(usage(response)) }.getOrNull()
                outcome = "succeeded"
            }
        } catch (failure: Throwable) {
            if (isOpenAICancellation(failure)) outcome = "cancelled"
            throw classifyOpenAISdkFailure(failure)
        } finally {
            recorder.record(
                operation, stage, model, outcome, usageJson,
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                attempt = attempts.get().takeIf { it > 0 }, maxRetries = maxRetries,
                httpStatus = lastHttpStatus,
            )
        }
    }
}

internal fun isOpenAICancellation(failure: Throwable): Boolean =
    Thread.currentThread().isInterrupted || failure.causes().any { it is CancellationException || it is InterruptedException }

/** Preserve typed provider status for application retry decisions without exposing provider prose. */
internal fun classifyOpenAISdkFailure(failure: Throwable): Throwable {
    if (isOpenAICancellation(failure)) return failure
    val provider = failure.causes().filterIsInstance<OpenAIServiceException>().firstOrNull() ?: return failure
    val status = provider.statusCode()
    // SDK Optional accessors can still throw when a malformed error omits a required field.
    val code = runCatching { provider.code().orElse(null) }.getOrNull()
    val type = runCatching { provider.type().orElse(null) }.getOrNull()
    val permanentQuota = status == 429 && (
        code in setOf("credit_balance_exhausted", "insufficient_quota") || type == "insufficient_quota"
        )
    return OpenAIRequestFailure(
        retryable = !permanentQuota && (status == 408 || status == 429 || status in 500..599),
        cause = failure,
    )
}

private fun Throwable.causes(): Sequence<Throwable> = sequence {
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var next: Throwable? = this@causes
    while (next != null && visited.add(next)) {
        yield(next)
        next = next.cause
    }
}

/** Bypass Spring's EmptyUsage/default zero totals; only native provider fields are evidence. */
internal fun openAISdkUsageJson(nativeUsage: Any?): JsonNode? = when (nativeUsage) {
    is CompletionUsage -> JsonMapperProvider.mapper.createObjectNode().apply {
        put("prompt_tokens", nativeUsage.promptTokens())
        put("completion_tokens", nativeUsage.completionTokens())
        put("total_tokens", nativeUsage.totalTokens())
        nativeUsage.promptTokensDetails().orElse(null)?.let { details ->
            putObject("prompt_tokens_details").apply {
                putNullable("cached_tokens", details.cachedTokens().orElse(null))
                putNullable("audio_tokens", details.audioTokens().orElse(null))
            }
        }
        nativeUsage.completionTokensDetails().orElse(null)?.let { details ->
            putObject("completion_tokens_details").apply {
                putNullable("audio_tokens", details.audioTokens().orElse(null))
                putNullable("reasoning_tokens", details.reasoningTokens().orElse(null))
                putNullable("accepted_prediction_tokens", details.acceptedPredictionTokens().orElse(null))
                putNullable("rejected_prediction_tokens", details.rejectedPredictionTokens().orElse(null))
            }
        }
    }
    is CreateEmbeddingResponse.Usage -> JsonMapperProvider.mapper.createObjectNode().apply {
        put("prompt_tokens", nativeUsage.promptTokens())
        put("total_tokens", nativeUsage.totalTokens())
    }
    else -> null
}
