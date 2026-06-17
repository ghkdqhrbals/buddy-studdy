package com.buddystuddy.backend.community.adapter.outbound.translation

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.application.port.outbound.QuestionSearchTranslationPort
import com.buddystuddy.backend.study.application.port.outbound.TranslatedQuestionSearchText
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class LibreTranslateQuestionSearchTranslator(
    properties: BuddyStuddyProperties,
) : QuestionSearchTranslationPort {
    private val rest = RestClient.builder()
        .baseUrl(properties.translation.baseUrl.trimEnd('/'))
        .build()

    override fun translateSearchText(
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

    private fun String?.translateNullable(sourceLanguage: String, targetLanguage: String): String? =
        this?.takeIf { it.isNotBlank() }?.let { translate(it, sourceLanguage, targetLanguage) }

    private fun translate(text: String, sourceLanguage: String, targetLanguage: String): String {
        if (text.isBlank() || sourceLanguage == targetLanguage) return text
        val response = rest.post()
            .uri("/translate")
            .body(
                mapOf(
                    "q" to text,
                    "source" to sourceLanguage,
                    "target" to targetLanguage,
                    "format" to "text",
                )
            )
            .retrieve()
            .body(LibreTranslateResponse::class.java)
        return response?.translatedText?.takeIf { it.isNotBlank() } ?: text
    }
}

private data class LibreTranslateResponse(
    val translatedText: String = "",
)
