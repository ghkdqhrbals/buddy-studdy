package com.buddystudy.backend.community.adapter.outbound.translation

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.QuestionSearchTranslationPort
import com.buddystudy.backend.study.application.port.outbound.TranslatedQuestionSearchText
import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Component
class LibreTranslateQuestionSearchTranslator(
    properties: BuddyStudyProperties,
    webClientBuilder: WebClient.Builder,
    private val objectMapper: ObjectMapper,
) : QuestionSearchTranslationPort {
    private val client = webClientBuilder
        .baseUrl(properties.translation.baseUrl.trimEnd('/'))
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                    .responseTimeout(RESPONSE_TIMEOUT),
            ),
        )
        .build()

    override suspend fun translateSearchText(
        sourceLanguage: String,
        targetLanguage: String,
        topic: String,
        question: String,
        answer: String?,
        feedback: String?,
        explanation: String?,
    ): TranslatedQuestionSearchText =
        TranslatedQuestionSearchText(
            topic = translate(topic, sourceLanguage, targetLanguage),
            question = translate(question, sourceLanguage, targetLanguage),
            answer = answer.translateNullable(sourceLanguage, targetLanguage),
            feedback = feedback.translateNullable(sourceLanguage, targetLanguage),
            explanation = explanation.translateNullable(sourceLanguage, targetLanguage),
        )

    private suspend fun String?.translateNullable(sourceLanguage: String, targetLanguage: String): String? =
        this?.takeIf { it.isNotBlank() }?.let { translate(it, sourceLanguage, targetLanguage) }

    private suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String {
        if (text.isBlank() || sourceLanguage == targetLanguage) return text
        val response = client.post()
            .uri("/translate")
            .bodyValue(
                mapOf(
                    "q" to text,
                    "source" to sourceLanguage,
                    "target" to targetLanguage,
                    "format" to "text",
                ),
            )
            .retrieve()
            .bodyToMono(String::class.java)
            .awaitSingleOrNull()
        return response
            ?.let(objectMapper::readTree)
            ?.path("translatedText")
            ?.asText()
            ?.takeIf(String::isNotBlank)
            ?: text
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(8)
    }
}
