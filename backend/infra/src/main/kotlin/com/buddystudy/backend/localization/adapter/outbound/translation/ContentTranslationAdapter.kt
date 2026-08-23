package com.buddystudy.backend.localization.adapter.outbound.translation

import com.buddystudy.backend.localization.application.model.ContentTranslationResult
import com.buddystudy.backend.localization.application.port.ContentTranslationPort
import com.buddystudy.backend.study.application.port.outbound.QuestionTranslationPort
import com.buddystudy.backend.study.application.port.outbound.TranslationValidationMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Component

@Component
class ContentTranslationAdapter(
    private val questions: QuestionTranslationPort,
) : ContentTranslationPort {
    override suspend fun translate(
        fields: Map<String, String?>,
        sourceLanguages: Map<String, String>,
        targetLanguage: String,
    ): ContentTranslationResult = coroutineScope {
        if (fields.isEmpty()) {
            return@coroutineScope ContentTranslationResult(emptyMap(), "identity")
        }
        val translated = fields.map { (name, value) ->
            async {
                if (value.isNullOrBlank()) {
                    name to value
                } else {
                    val result = questions.translate(
                        topic = "CONTENT",
                        question = value,
                        hint = null,
                        sourceLanguage = sourceLanguages[name] ?: "ko",
                        targetLanguage = targetLanguage,
                        validationMode = if (name == "question") {
                            TranslationValidationMode.QUESTION
                        } else {
                            TranslationValidationMode.SHORT_TEXT
                        },
                    )
                    name to result.question
                }
            }
        }.awaitAll().toMap()
        ContentTranslationResult(translated, "provider-chain")
    }
}
