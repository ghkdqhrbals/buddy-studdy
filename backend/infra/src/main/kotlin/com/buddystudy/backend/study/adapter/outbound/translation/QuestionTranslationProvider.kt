package com.buddystudy.backend.study.adapter.outbound.translation

import com.buddystudy.backend.study.application.model.TranslatedQuestionContent

data class QuestionTranslationRequest(
    val topic: String,
    val question: String,
    val hint: String?,
    val sourceLanguage: String,
)

interface QuestionTranslationProvider {
    val providerId: String

    suspend fun translate(request: QuestionTranslationRequest): TranslatedQuestionContent
}
