package com.buddystudy.backend.externalapi.adapter.outbound.usage

import com.buddystudy.backend.study.application.openai.OpenAIRequestFailure
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class OpenAISdkUsageCallTest {
    @Test
    fun `actual SDK retry records two transport attempts and charges reported usage once`() {
        val recorder = CapturingOpenAIUsageRecorder()
        val call = OpenAISdkUsageCall(recorder, "grading", "evidence", "fixture-model", 1)
        val response = sdkCall(call, retries = 1, replies = listOf(
            429 to errorBody("rate_limit_exceeded", "rate_limit_exceeded"), 200 to SUCCESS,
        ))
        assertThat(requireNotNull(response.result).output.text).isEqualTo("fixture answer")
        val attempts = recorder.events.filter { it["granularity"].asText() == "physical_attempt" }
        assertThat(attempts.map { it["attempt"].asInt() }).containsExactly(1, 2)
        assertThat(attempts.map { it["outcome"].asText() }).containsExactly("failed", "succeeded")
        assertThat(attempts.map { it["httpStatus"].asInt() }).containsExactly(429, 200)
        assertThat(attempts.all { !it["usageAvailable"].asBoolean() }).isTrue()
        val logical = recorder.events.single { it["granularity"].asText() == "logical" }
        assertThat(logical["inputTokens"].asLong()).isEqualTo(11)
        assertThat(logical["outputTokens"].asLong()).isEqualTo(3)
        assertThat(logical["retryCount"].asInt()).isEqualTo(1)
        assertThat(logical["stage"].asText()).isEqualTo("evidence")
        assertThat(recorder.events.joinToString()).doesNotContain("fixture answer", "private input", "fixture-key")
    }

    @Test
    fun `typed SDK quota auth and transient failures retain exact application retryability`() {
        listOf(
            Triple(429, errorBody("credit_balance_exhausted", "insufficient_quota"), false),
            Triple(429, errorBody("insufficient_quota", "insufficient_quota"), false),
            Triple(429, errorBody("rate_limit_exceeded", "rate_limit_exceeded"), true),
            Triple(500, errorBody("server_error", "server_error"), true),
            Triple(408, errorBody("request_timeout", "request_timeout"), true),
            Triple(401, errorBody("invalid_api_key", "authentication_error"), false),
            Triple(400, errorBody("invalid_request", "invalid_request_error"), false),
            // Free-form prose alone must not impersonate a structured permanent quota error.
            Triple(429, """{"error":{"message":"insufficient_quota credit_balance_exhausted"}}""", true),
        ).forEach { (status, body, retryable) ->
            val recorder = CapturingOpenAIUsageRecorder()
            val call = OpenAISdkUsageCall(recorder, "question", "request", "fixture-model", 0)
            val failure = runCatching { sdkCall(call, 0, listOf(status to body)) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(OpenAIRequestFailure::class.java)
            assertThat((failure as OpenAIRequestFailure).retryable).describedAs("HTTP %s", status).isEqualTo(retryable)
            assertThat(failure.message).isEqualTo("OpenAI request failed.")
            assertThat(recorder.events.last()["usageAvailable"].asBoolean()).isFalse()
            assertThat(recorder.events.last()["httpStatus"].asInt()).isEqualTo(status)
        }
    }

    @Test
    fun `cancellation is preserved with unknown usage and no fabricated transport attempt`() {
        val recorder = CapturingOpenAIUsageRecorder()
        val expected = CancellationException("private cancellation evidence")
        val call = OpenAISdkUsageCall(recorder, "grading", "critic", "fixture-model", 1)
        val failure = runCatching { call.execute<Unit>(usage = { null }) { throw expected } }.exceptionOrNull()
        assertThat(failure).isSameAs(expected)
        val event = recorder.events.single()
        assertThat(event["outcome"].asText()).isEqualTo("cancelled")
        assertThat(event["attempt"].isNull).isTrue()
        assertThat(event["usageAvailable"].asBoolean()).isFalse()
        assertThat(event.toString()).doesNotContain("private cancellation")
    }

    @Test
    fun `last transport failure cannot inherit an earlier attempt HTTP status`() {
        val recorder = CapturingOpenAIUsageRecorder()
        val call = OpenAISdkUsageCall(recorder, "question", "request", "fixture-model", 1)
        assertThat(runCatching { sdkCall(call, 1, listOf(
            429 to errorBody("rate_limit_exceeded", "rate_limit_exceeded"), -1 to "",
        )) }.isFailure).isTrue()
        val logical = recorder.events.single { it["granularity"].asText() == "logical" }
        assertThat(logical["outcome"].asText()).isEqualTo("failed")
        assertThat(logical["attempt"].asInt()).isEqualTo(2)
        assertThat(logical["httpStatus"].isNull).isTrue()
        assertThat(logical["usageAvailable"].asBoolean()).isFalse()
    }

    @Test
    fun `successful provider output survives unavailable usage and a broken telemetry sink`() {
        val recorder = object : OpenAIUsageRecorder() {
            override fun emit(event: ObjectNode) { error("broken sink") }
        }
        val call = OpenAISdkUsageCall(recorder, "question", "rubric", "fixture-model", 0)
        val response = sdkCall(call, 0, listOf(200 to SUCCESS.replace(
            ",\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3,\"total_tokens\":14}", "",
        )))
        assertThat(requireNotNull(response.result).output.text).isEqualTo("fixture answer")
    }

    private fun sdkCall(call: OpenAISdkUsageCall, retries: Int, replies: List<Pair<Int, String>>): ChatResponse {
        val cursor = AtomicInteger()
        val options = OpenAiChatOptions.builder().apiKey("fixture-key").model("fixture-model")
            .baseUrl("https://fixture.invalid").timeout(Duration.ofSeconds(5)).maxRetries(retries).build()
        return call.execute(usage = { response: ChatResponse -> response.metadata.usage.nativeUsage }) {
            OpenAiChatModel.builder().options(options)
                .httpClientBuilderCustomizer(call.httpClientCustomizer)
                .httpClientBuilderCustomizer { builder ->
                    // Terminal synthetic interceptor: no network, credential, DB, or paid API is used.
                    builder.interceptor { chain ->
                        val (status, body) = replies[cursor.getAndIncrement().coerceAtMost(replies.lastIndex)]
                        if (status == -1) throw java.io.IOException("fixture connection failure")
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(status).message("fixture").header("retry-after-ms", "1")
                            .body(body.toResponseBody("application/json".toMediaType())).build()
                    }
                }.build().call(Prompt("private input", options))
        }
    }

    private fun errorBody(code: String, type: String) =
        """{"error":{"code":"$code","type":"$type","message":"private provider error"}}"""

    private companion object {
        const val SUCCESS = """{"id":"fixture","object":"chat.completion","created":1,"model":"fixture-model","choices":[{"index":0,"message":{"role":"assistant","content":"fixture answer"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}"""
    }
}
