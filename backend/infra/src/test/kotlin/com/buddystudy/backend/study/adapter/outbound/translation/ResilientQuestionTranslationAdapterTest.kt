package com.buddystudy.backend.study.adapter.outbound.translation

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResilientQuestionTranslationAdapterTest {
    @Test
    fun `uses providers in configured order`() = runBlocking {
        val openAI = RecordingProvider(
            providerId = "openai",
            result = TranslatedQuestionContent(
                topic = "Advice types",
                question = "Explain the differences between advice types in Spring AOP.",
                hint = null,
            ),
        )
        val libre = RecordingProvider(providerId = "libretranslate")
        val adapter = adapter(listOf(libre, openAI), listOf("openai", "libretranslate"))

        val translated = adapter.translateToEnglish("어드바이스 종류", "Spring AOP의 차이를 설명하세요.", null, "ko")

        assertThat(translated.topic).isEqualTo("Advice types")
        assertThat(openAI.calls).hasSize(1)
        assertThat(libre.calls).isEmpty()
    }

    @Test
    fun `falls back when the primary provider fails`() = runBlocking {
        val openAI = RecordingProvider(providerId = "openai", failure = IllegalStateException("unavailable"))
        val libre = RecordingProvider(
            providerId = "libretranslate",
            result = TranslatedQuestionContent(
                topic = "Proxy creation",
                question = "Explain how proxy creation works for interface-based services.",
                hint = "Consider gradual rollout.",
            ),
        )
        val registry = SimpleMeterRegistry()
        val adapter = adapter(listOf(openAI, libre), listOf("openai", "libretranslate"), registry)

        val translated = adapter.translateToEnglish("프록시 생성", "인터페이스 프록시를 설명하세요.", null, "ko")

        assertThat(translated.topic).isEqualTo("Proxy creation")
        assertThat(openAI.calls).hasSize(1)
        assertThat(libre.calls).hasSize(1)
        assertThat(
            registry.counter(
                "buddystudy.translation.requests",
                "provider", "openai",
                "outcome", "failure",
            ).count(),
        ).isEqualTo(1.0)
    }

    private fun adapter(
        providers: List<QuestionTranslationProvider>,
        order: List<String>,
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
    ): ResilientQuestionTranslationAdapter {
        val properties = BuddyStudyProperties()
        properties.translation.providerOrder = order
        return ResilientQuestionTranslationAdapter(providers, properties, registry)
    }

    private class RecordingProvider(
        override val providerId: String,
        private val result: TranslatedQuestionContent? = null,
        private val failure: Exception? = null,
    ) : QuestionTranslationProvider {
        val calls = mutableListOf<QuestionTranslationRequest>()

        override suspend fun translate(request: QuestionTranslationRequest): TranslatedQuestionContent {
            calls += request
            failure?.let { throw it }
            return checkNotNull(result) { "No result configured." }
        }
    }
}
