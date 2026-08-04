package com.buddystudy.backend.study.adapter.outbound.translation

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.supervisorScope
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

    override suspend fun translate(request: QuestionTranslationRequest): TranslatedQuestionContent = supervisorScope {
        val topic = async { translateTextResult(request.topic, request.sourceLanguage, request.targetLanguage) }
        val question = async { translateTextResult(request.question, request.sourceLanguage, request.targetLanguage) }
        val hint = request.hint
            ?.takeIf(String::isNotBlank)
            ?.let { value -> async { translateTextResult(value, request.sourceLanguage, request.targetLanguage) } }

        val topicResult = topic.await()
        val questionResult = question.await()
        val hintResult = hint?.await()

        TranslatedQuestionContent(
            topic = topicResult.getOrThrow(),
            question = questionResult.getOrThrow(),
            hint = hintResult?.getOrThrow(),
        )
    }

    private suspend fun translateTextResult(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): Result<String> = try {
        Result.success(translateText(text, sourceLanguage, targetLanguage))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun translateText(text: String, sourceLanguage: String, targetLanguage: String): String {
        val body = linkedMapOf<String, Any>(
            "q" to text,
            "source" to sourceLanguage,
            "target" to targetLanguage,
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
