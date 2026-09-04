package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.VoiceTutorInputAssessmentProperties
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentFailure
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputUtterance
import com.buddystudy.backend.voice.application.model.VoiceTutorSpokenFeedbackAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorSpokenQuestionAssessmentRequest
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.http.HttpStatus
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import reactor.core.publisher.Mono

class VoiceTutorInputAssessmentModelTest {
    private val mapper = JsonMapperProvider.mapper

    @Test
    fun `unset and blank overrides preserve configured summary model on all assessment entry points`() = runBlocking<Unit> {
        for (override in listOf(null, "", " \t")) {
            val properties = properties("configured-summary-model")
            val bodies = captureAssessmentRequests(properties, VoiceTutorInputAssessmentProperties(model = override))

            assertThat(bodies).hasSize(3)
            bodies.forEach { body ->
                assertThat(body.path("model").asText()).isEqualTo("configured-summary-model")
                assertThat(body.has("reasoning_effort")).isFalse()
            }
            assertThat(properties.voiceTutor.summaryModel).isEqualTo("configured-summary-model")
        }
    }

    @Test
    fun `mini override reaches every semantic entry point without changing summary configuration`() = runBlocking<Unit> {
        val properties = properties("gpt-5.4")
        val bodies = captureAssessmentRequests(
            properties,
            VoiceTutorInputAssessmentProperties(model = " gpt-5.4-mini "),
        )

        assertThat(bodies).hasSize(3)
        bodies.forEach { body ->
            assertThat(body.path("model").asText()).isEqualTo("gpt-5.4-mini")
            assertThat(body.path("reasoning_effort").asText()).isEqualTo("none")
        }
        assertThat(properties.voiceTutor.summaryModel).isEqualTo("gpt-5.4")
    }

    @Test
    fun `all six semantic prompt types disable reasoning only on documented matching models`() {
        val supported = listOf("gpt-5.4", "gpt-5.4-2026-03-05", "gpt-5.4-mini", "gpt-5.4-mini-2026-03-17")
        for (model in supported + listOf("gpt-4.1-mini", "gpt-5.4-mini-unverified")) {
            // These empty attestation envelopes are never sent; this contract
            // tests only the common model options, not semantic output schemas.
            val bodies = listOf(
                VoiceTutorInputAssessmentPromptProvider.requestBody(input(), model),
                VoiceTutorSpokenQuestionPromptProvider.requestBody(question(), model),
                VoiceTutorSpokenFeedbackPromptProvider.requestBody(feedback(), model),
                VoiceTutorRootCreationAttestationPromptProvider.requestBody(
                    VoiceTutorRootCreationAttestationRequest("ko", emptyList()), model,
                ),
                VoiceTutorStudyMutationAttestationPromptProvider.requestBody(
                    VoiceTutorStudyMutationAttestationRequest("ko", emptyList()), model,
                ),
                VoiceTutorStudyMutationConfirmationPromptProvider.requestBody(
                    VoiceTutorStudyMutationConfirmationRequest("ko", emptyList()), model,
                ),
            )
            bodies.forEach { body ->
                assertThat(body["model"]).isEqualTo(model)
                if (model in supported) assertThat(body["reasoning_effort"]).isEqualTo("none")
                else assertThat(body).doesNotContainKey("reasoning_effort")
            }
        }
    }

    @Test
    fun `optional assessment model binds separately and survives the operational limits snapshot`() {
        assertThat(VoiceTutorInputAssessmentProperties().model).isNull()
        val bound = Binder(MapConfigurationPropertySource(mapOf(
            "buddystudy.voice-tutor.input-assessment.model" to "gpt-5.4-mini",
            "buddystudy.voice-tutor.input-assessment.timeout-milliseconds" to "1200",
        ))).bind(
            "buddystudy.voice-tutor.input-assessment",
            Bindable.of(VoiceTutorInputAssessmentProperties::class.java),
        ).get()

        assertThat(bound.model).isEqualTo("gpt-5.4-mini")
        assertThat(bound.copy().model).isEqualTo("gpt-5.4-mini")
        assertThat(bound.timeoutMilliseconds).isEqualTo(1_200)
        assertThat(bound.maxConcurrentAssessments).isEqualTo(4)
    }

    private suspend fun captureAssessmentRequests(
        properties: BuddyStudyProperties,
        assessmentProperties: VoiceTutorInputAssessmentProperties,
    ): List<JsonNode> {
        val bodies = mutableListOf<JsonNode>()
        val adapter = OpenAIVoiceTutorInputAssessmentAdapter(
            UserContentOpenAIKeyProvider(properties), properties,
            ExchangeFunction { request ->
                val output = MockClientHttpRequest(request.method(), request.url())
                request.writeTo(output, ExchangeStrategies.withDefaults()).then(Mono.defer {
                    output.bodyAsString.map { raw ->
                        bodies.add(mapper.readTree(raw))
                        ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()
                    }
                })
            },
            assessmentProperties,
        )
        val calls: List<suspend () -> Unit> = listOf(
            { adapter.assess(input()); Unit },
            { adapter.assessSpokenQuestion(question()); Unit },
            { adapter.assessSpokenFeedback(feedback()); Unit },
        )
        calls.forEach { call ->
            val error = runCatching { call() }.exceptionOrNull() as VoiceTutorInputAssessmentException
            assertThat(error.reason).isEqualTo(VoiceTutorInputAssessmentFailure.UNAVAILABLE)
        }
        return bodies
    }

    private fun properties(summaryModel: String) = BuddyStudyProperties().apply {
        openai.userContentApiKey = "sk-synthetic-model-routing-test"
        voiceTutor.summaryModel = summaryModel
    }

    private fun input() = VoiceTutorInputAssessmentRequest(7, "ko", "", listOf(VoiceTutorInputUtterance("item", "네")))
    private fun question() = VoiceTutorSpokenQuestionAssessmentRequest(7, "ko", "Spring", 5, "DI란 무엇인가요?")
    private fun feedback() = VoiceTutorSpokenFeedbackAssessmentRequest(
        7, "ko", "Spring", 5, "DI란 무엇인가요?", "의존성 주입입니다.", "80점입니다. 핵심 용어를 짚었습니다.",
    )
}
