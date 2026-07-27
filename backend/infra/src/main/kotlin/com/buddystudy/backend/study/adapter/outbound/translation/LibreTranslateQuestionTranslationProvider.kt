package com.buddystudy.backend.study.adapter.outbound.translation

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration

@Component
class LibreTranslateQuestionTranslationProvider(
    webClientBuilder: WebClient.Builder,
    private val properties: BuddyStudyProperties,
) : QuestionTranslationProvider {
    override val providerId: String = "libretranslate"
    private val client = webClientBuilder.clone()
        .baseUrl(properties.translation.baseUrl.trimEnd('/'))
        .build()

    override suspend fun translate(request: QuestionTranslationRequest): TranslatedQuestionContent = coroutineScope {
        val topic = async { translateText(request.topic, request.sourceLanguage) }
        val question = async { translateText(request.question, request.sourceLanguage) }
        val hint = request.hint
            ?.takeIf(String::isNotBlank)
            ?.let { value -> async { translateText(value, request.sourceLanguage) } }

        TranslatedQuestionContent(
            topic = topic.await(),
            question = question.await(),
            hint = hint?.await(),
        )
    }

    private suspend fun translateText(text: String, sourceLanguage: String): String {
        val body = linkedMapOf<String, Any>(
            "q" to text,
            "source" to sourceLanguage,
            "target" to "en",
            "format" to "text",
        )
        properties.translation.apiKey.takeIf(String::isNotBlank)?.let { body["api_key"] = it }
        val response = client.post()
            .uri("/translate")
            .bodyValue(body)
            .retrieve()
            .bodyToMono<LibreTranslateResponse>()
            .timeout(Duration.ofMillis(properties.translation.timeoutMs.coerceAtLeast(100)))
            .awaitSingle()
        return response.translatedText.trim().also {
            require(it.isNotBlank()) { "LibreTranslate returned empty content." }
        }
    }

    private data class LibreTranslateResponse(
        val translatedText: String = "",
    )
}
