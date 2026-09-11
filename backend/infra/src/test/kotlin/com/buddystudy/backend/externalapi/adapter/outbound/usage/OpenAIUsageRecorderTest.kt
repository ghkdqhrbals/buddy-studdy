package com.buddystudy.backend.externalapi.adapter.outbound.usage

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openai.models.completions.CompletionUsage
import com.openai.models.embeddings.CreateEmbeddingResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class OpenAIUsageRecorderTest {
    private val mapper = JsonMapperProvider.mapper

    @Test
    fun `chat usage preserves cached audio reasoning and explicit zero without inventing text totals`() {
        val usage = OpenAIUsageParser.parse(mapper.readTree("""{
            "prompt_tokens":120,"completion_tokens":40,"total_tokens":160,
            "prompt_tokens_details":{"cached_tokens":0,"audio_tokens":35},
            "completion_tokens_details":{"reasoning_tokens":25,"audio_tokens":5,
                "accepted_prediction_tokens":2,"rejected_prediction_tokens":1}
        }"""))
        assertThat(usage["inputTokens"].asLong()).isEqualTo(120)
        assertThat(usage["outputTokens"].asLong()).isEqualTo(40)
        assertThat(usage["totalTokens"].asLong()).isEqualTo(160)
        assertThat(usage["cachedInputTokens"].asLong()).isZero()
        assertThat(usage["reasoningTokens"].asLong()).isEqualTo(25)
        assertThat(usage["inputAudioTokens"].asLong()).isEqualTo(35)
        assertThat(usage["outputAudioTokens"].asLong()).isEqualTo(5)
        assertThat(usage["acceptedPredictionTokens"].asLong()).isEqualTo(2)
        assertThat(usage["rejectedPredictionTokens"].asLong()).isEqualTo(1)
        assertThat(usage["inputTextTokens"].isNull).isTrue()
        assertThat(usage["outputTextTokens"].isNull).isTrue()
    }

    @Test
    fun `realtime preserves explicit modality and cached breakdown as subsets`() {
        val usage = OpenAIUsageParser.parse(mapper.readTree("""{
            "input_tokens":100,"output_tokens":50,"total_tokens":150,
            "input_token_details":{"text_tokens":20,"audio_tokens":70,"image_tokens":10,"cached_tokens":60,
                "cached_tokens_details":{"text_tokens":10,"audio_tokens":45,"image_tokens":5}},
            "output_token_details":{"text_tokens":10,"audio_tokens":40}
        }"""))
        assertThat(usage["cachedInputTokens"].asLong()).isEqualTo(60)
        assertThat(usage["cachedTextTokens"].asLong()).isEqualTo(10)
        assertThat(usage["cachedAudioTokens"].asLong()).isEqualTo(45)
        assertThat(usage["cachedImageTokens"].asLong()).isEqualTo(5)
        assertThat(usage["inputImageTokens"].asLong()).isEqualTo(10)
        assertThat(usage["outputTextTokens"].asLong()).isEqualTo(10)
        assertThat(usage["reasoningTokens"].isNull).isTrue()
    }

    @Test
    fun `responses and duration transcription usage keep provider shape distinctions`() {
        val response = OpenAIUsageParser.parse(mapper.readTree("""{
            "input_tokens":12,"output_tokens":8,"input_tokens_details":{"cached_tokens":4},
            "output_tokens_details":{"reasoning_tokens":3}
        }"""))
        assertThat(response["cachedInputTokens"].asLong()).isEqualTo(4)
        assertThat(response["reasoningTokens"].asLong()).isEqualTo(3)
        assertThat(response["totalTokens"].isNull).isTrue()
        val duration = OpenAIUsageParser.parse(mapper.readTree("""{"type":"duration","seconds":1.25}"""))
        assertThat(duration["audioSeconds"].asDouble()).isEqualTo(1.25)
        assertThat(duration["usageAvailable"].asBoolean()).isTrue()
        assertThat(duration["inputTokens"].isNull).isTrue()
    }

    @Test
    fun `missing malformed negative and overflowing usage remains unknown instead of zero`() {
        listOf(null, mapper.readTree("{}"), mapper.readTree("[]"), mapper.readTree("""{
            "input_tokens":"12","output_tokens":-1,"total_tokens":999999999999999999999999,
            "input_token_details":{"audio_tokens":2.5},"seconds":2
        }""")).forEach { input ->
            val usage = OpenAIUsageParser.parse(input)
            assertThat(usage["usageAvailable"].asBoolean()).isFalse()
            assertThat(usage.fields().asSequence().filter { it.key != "usageAvailable" }.all { it.value.isNull }).isTrue()
        }
    }

    @Test
    fun `sdk native usage ignores missing usage and embedding output default`() {
        assertThat(openAISdkUsageJson(null)).isNull()
        assertThat(openAISdkUsageJson(org.springframework.ai.chat.metadata.EmptyUsage())).isNull()
        val embedding = openAISdkUsageJson(CreateEmbeddingResponse.Usage.builder().promptTokens(9).totalTokens(9).build())
        assertThat(OpenAIUsageParser.parse(embedding)["outputTokens"].isNull).isTrue()
        val chat = CompletionUsage.builder().promptTokens(9).completionTokens(3).totalTokens(12)
            .completionTokensDetails(CompletionUsage.CompletionTokensDetails.builder().reasoningTokens(2).build()).build()
        assertThat(OpenAIUsageParser.parse(openAISdkUsageJson(chat))["reasoningTokens"].asLong()).isEqualTo(2)
    }

    @Test
    fun `event excludes content and identifiers and limits routing values`() {
        val recorder = CapturingOpenAIUsageRecorder()
        recorder.record("private transcript", "Bearer credential", "sk-secret", "private error", mapper.readTree("""{
            "input_tokens":12,"transcript":"private transcript","api_key":"sk-secret","response_id":"raw-response"
        }"""), eventRef = "raw-response", attempt = -1, durationMs = -1)
        val event = recorder.events.single()
        assertThat(event["operation"].asText()).isEqualTo("other")
        assertThat(event["stage"].asText()).isEqualTo("other")
        assertThat(event["model"].isNull).isTrue()
        assertThat(event["attempt"].isNull).isTrue()
        assertThat(event["retryCount"].isNull).isTrue()
        assertThat(event["durationMs"].isNull).isTrue()
        assertThat(event["eventRef"].asText()).matches("[a-f0-9]{32}")
        assertThat(event.toString()).doesNotContain("private transcript", "Bearer", "sk-secret", "raw-response", "api_key")
    }

    @Test
    fun `attempt identity and supplied hashed event reference remain explicit`() {
        val recorder = CapturingOpenAIUsageRecorder()
        recorder.record("grading", "adjudication", "gpt-5.4", "failed", attempt = 3, maxRetries = 2,
            httpStatus = 429, durationMs = 123, granularity = "physical_attempt", eventRef = "0123456789abcdef")
        val event = recorder.events.single()
        assertThat(event["attempt"].asInt()).isEqualTo(3)
        assertThat(event["retryCount"].asInt()).isEqualTo(2)
        assertThat(event["usageAvailable"].asBoolean()).isFalse()
        assertThat(event["eventRef"].asText()).isEqualTo("0123456789abcdef")
    }

    @Test
    fun `broken telemetry sink cannot fail provider work`() {
        val recorder = object : OpenAIUsageRecorder() {
            override fun emit(event: ObjectNode) { error("sink unavailable") }
        }
        assertThatCode { recorder.record("question", "request", "gpt-5.4", "succeeded") }.doesNotThrowAnyException()
    }
}

internal class CapturingOpenAIUsageRecorder : OpenAIUsageRecorder() {
    val events = java.util.concurrent.CopyOnWriteArrayList<ObjectNode>()
    override fun emit(event: ObjectNode) { events += event }
}
