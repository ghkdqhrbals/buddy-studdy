package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.externalapi.adapter.outbound.usage.CapturingOpenAIUsageRecorder
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.voice.application.model.VoiceTutorRealtimeVoice
import com.buddystudy.backend.voice.application.model.VoiceTutorSpokenQuestionAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewLanguage
import com.buddystudy.backend.voice.application.model.VoiceTutorVoicePreviewRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class OpenAIVoiceTutorUsageTelemetryTest {
    @Test
    fun `spoken assessment records provider usage and bounded stage without utterance or credentials`() = runBlocking<Unit> {
        val recorder = CapturingOpenAIUsageRecorder()
        val adapter = assessment(recorder, ExchangeFunction {
            Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("""{"choices":[{"message":{"role":"assistant","content":"{\"isStudyQuestion\":true}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25}}""")
                .build())
        })
        assertThat(adapter.assessSpokenQuestion(question())).isTrue()
        val event = recorder.events.single()
        assertThat(event["operation"].asText()).isEqualTo("voice_input_assessment")
        assertThat(event["stage"].asText()).isEqualTo("spoken_question")
        assertThat(event["inputTokens"].asLong()).isEqualTo(20)
        assertThat(event["granularity"].asText()).isEqualTo("logical")
        assertThat(event["retryCount"].asInt()).isZero()
        assertThat(event.toString()).doesNotContain("private-learner-question", "private-key", "userId", "safety_identifier")
    }

    @Test
    fun `assessment failures and cancellation have unknown usage without altering outcomes`() = runBlocking<Unit> {
        val failedRecorder = CapturingOpenAIUsageRecorder()
        val failed = assessment(failedRecorder, ExchangeFunction {
            Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).body("private provider body").build())
        })
        assertThat(runCatching { failed.assessSpokenQuestion(question()) }.isFailure).isTrue()
        assertThat(failedRecorder.events.single()["outcome"].asText()).isEqualTo("failed")
        assertThat(failedRecorder.events.single()["httpStatus"].asInt()).isEqualTo(429)
        assertThat(failedRecorder.events.single()["usageAvailable"].asBoolean()).isFalse()
        val cancelledRecorder = CapturingOpenAIUsageRecorder()
        val cancelled = assessment(cancelledRecorder, ExchangeFunction { Mono.never() })
        val task = async(start = CoroutineStart.UNDISPATCHED) { cancelled.assessSpokenQuestion(question()) }
        task.cancelAndJoin()
        assertThat(cancelledRecorder.events.single()["outcome"].asText()).isEqualTo("cancelled")
        assertThat(cancelledRecorder.events.single()["httpStatus"].isNull).isTrue()
    }

    @Test
    fun `binary voice preview logs its actual request without inventing audio or token usage`() = runBlocking<Unit> {
        val recorder = CapturingOpenAIUsageRecorder()
        val properties = properties()
        val adapter = OpenAIVoiceTutorVoicePreviewAdapter(UserContentOpenAIKeyProvider(properties), properties,
            ExchangeFunction {
                Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                    .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(byteArrayOf(1, 2, 3)))).build())
            }, usageRecorder = recorder)
        assertThat(adapter.synthesize(VoiceTutorVoicePreviewRequest(VoiceTutorRealtimeVoice.MARIN, VoiceTutorVoicePreviewLanguage.ENGLISH)).bytes)
            .containsExactly(1, 2, 3)
        val event = recorder.events.single()
        assertThat(event["operation"].asText()).isEqualTo("voice_preview")
        assertThat(event["outcome"].asText()).isEqualTo("succeeded")
        assertThat(event["usageAvailable"].asBoolean()).isFalse()
        assertThat(event["inputTokens"].isNull).isTrue()
        assertThat(event["audioSeconds"].isNull).isTrue()
    }

    private fun assessment(recorder: CapturingOpenAIUsageRecorder, exchange: ExchangeFunction): OpenAIVoiceTutorInputAssessmentAdapter {
        val properties = properties()
        return OpenAIVoiceTutorInputAssessmentAdapter(UserContentOpenAIKeyProvider(properties), properties, exchange,
            usageRecorder = recorder)
    }

    private fun properties() = BuddyStudyProperties().apply {
        openai.userContentApiKey = "private-key"
        voiceTutor.summaryModel = "gpt-5.4"
    }

    private fun question() = VoiceTutorSpokenQuestionAssessmentRequest(
        userId = 7, language = "ko", focusTopic = "Redis", focusDifficulty = 5, transcript = "private-learner-question",
    )
}
