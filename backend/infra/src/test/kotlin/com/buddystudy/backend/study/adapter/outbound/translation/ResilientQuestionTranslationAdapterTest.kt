package com.buddystudy.backend.study.adapter.outbound.translation

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.port.outbound.TranslationValidationMode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ResilientQuestionTranslationAdapterTest {
    @Test
    fun `prioritizes LibreTranslate by default`() = runBlocking {
        val libre = RecordingProvider(
            providerId = "libretranslate",
            result = TranslatedQuestionContent(
                topic = "Advice types",
                question = "Explain the differences between advice types in Spring AOP.",
                hint = null,
            ),
        )
        val openAI = RecordingProvider(providerId = "openai")
        val adapter = ResilientQuestionTranslationAdapter(
            listOf(openAI, libre),
            BuddyStudyProperties(),
            SimpleMeterRegistry(),
        )

        val translated = adapter.translateToEnglish("어드바이스 종류", "Spring AOP의 차이를 설명하세요.", null, "ko")

        assertThat(translated.topic).isEqualTo("Advice types")
        assertThat(libre.calls).hasSize(1)
        assertThat(openAI.calls).isEmpty()
    }

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

    @Test
    fun `does not turn coroutine cancellation into a provider fallback`() {
        val cancelled = RecordingProvider(
            providerId = "libretranslate",
            failure = CancellationException("request cancelled"),
        )
        val fallback = RecordingProvider(
            providerId = "openai",
            result = TranslatedQuestionContent(
                topic = "Proxy creation",
                question = "Explain how proxy creation works for interface-based services.",
                hint = null,
            ),
        )
        val adapter = adapter(listOf(cancelled, fallback), listOf("libretranslate", "openai"))

        assertThatThrownBy {
            runBlocking {
                adapter.translateToEnglish("프록시 생성", "인터페이스 프록시를 설명하세요.", null, "ko")
            }
        }.isInstanceOf(CancellationException::class.java)
        assertThat(fallback.calls).isEmpty()
    }

    @Test
    fun `accepts short translated content for answer feedback and comment fields`() = runBlocking {
        val libre = RecordingProvider(
            providerId = "libretranslate",
            result = TranslatedQuestionContent(
                topic = "Content",
                question = "OK",
                hint = null,
            ),
        )
        val openAI = RecordingProvider(providerId = "openai")
        val adapter = adapter(listOf(libre, openAI), listOf("libretranslate", "openai"))

        val translated = adapter.translate(
            topic = "Content",
            question = "확인",
            hint = null,
            sourceLanguage = "ko",
            targetLanguage = "en",
            validationMode = TranslationValidationMode.SHORT_TEXT,
        )

        assertThat(translated.question).isEqualTo("OK")
        assertThat(libre.calls).hasSize(1)
        assertThat(openAI.calls).isEmpty()
    }

    @Test
    fun `keeps sentence validation for study questions`() = runBlocking {
        val libre = RecordingProvider(
            providerId = "libretranslate",
            result = TranslatedQuestionContent(
                topic = "Content",
                question = "OK",
                hint = null,
            ),
        )
        val openAI = RecordingProvider(
            providerId = "openai",
            result = TranslatedQuestionContent(
                topic = "Content",
                question = "Explain why the operation should be acknowledged.",
                hint = null,
            ),
        )
        val adapter = adapter(listOf(libre, openAI), listOf("libretranslate", "openai"))

        val translated = adapter.translate(
            topic = "내용",
            question = "작업을 확인해야 하는 이유를 설명하세요.",
            hint = null,
            sourceLanguage = "ko",
            targetLanguage = "en",
        )

        assertThat(translated.question).startsWith("Explain why")
        assertThat(libre.calls).hasSize(1)
        assertThat(openAI.calls).hasSize(1)
    }

    @Test
    fun `returns preserved technical content without calling translation providers`() = runBlocking {
        val libre = RecordingProvider(providerId = "libretranslate")
        val openAI = RecordingProvider(providerId = "openai")
        val adapter = adapter(listOf(libre, openAI), listOf("libretranslate", "openai"))

        val translated = adapter.translate(
            topic = "HTTP API",
            question = "GET /api/v1/health/dependencies",
            hint = null,
            sourceLanguage = "en",
            targetLanguage = "ja",
        )

        assertThat(translated.question).isEqualTo("GET /api/v1/health/dependencies")
        assertThat(libre.calls).isEmpty()
        assertThat(openAI.calls).isEmpty()
    }

    @Test
    fun `rejects unchanged natural language before using the fallback provider`() = runBlocking {
        val libre = RecordingProvider(
            providerId = "libretranslate",
            result = TranslatedQuestionContent(
                topic = "Distributed systems",
                question = "Explain how Redis consumer groups work.",
                hint = null,
            ),
        )
        val openAI = RecordingProvider(
            providerId = "openai",
            result = TranslatedQuestionContent(
                topic = "分散システム",
                question = "Redisのコンシューマーグループの仕組みを説明してください。",
                hint = null,
            ),
        )
        val adapter = adapter(listOf(libre, openAI), listOf("libretranslate", "openai"))

        val translated = adapter.translate(
            topic = "Distributed systems",
            question = "Explain how Redis consumer groups work.",
            hint = null,
            sourceLanguage = "en",
            targetLanguage = "ja",
        )

        assertThat(translated.question).contains("説明してください")
        assertThat(libre.calls).hasSize(1)
        assertThat(openAI.calls).hasSize(1)
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
