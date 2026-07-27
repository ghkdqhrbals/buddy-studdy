package com.buddystudy.backend.study.application.model

import com.fasterxml.jackson.annotation.JsonProperty

enum class TranslationViewMode {
    LOCALIZED,
    ORIGINAL,
}

enum class TranslationState {
    ORIGINAL,
    TRANSLATED,
    PENDING,
    FAILED,
}

enum class TranslationReason {
    EXPLICIT_TL,
    ACCOUNT_LOCALE,
    ORIGINAL_VIEW,
}

data class ContentLocalizationResponse(
    val sourceLanguage: String,
    val requestedLanguage: String,
    val displayLanguage: String,
    val translationState: TranslationState,
    @get:JsonProperty("isTranslated")
    val isTranslated: Boolean,
    val originalAvailable: Boolean,
    val translationReason: TranslationReason,
)

data class RecordLocalizationResponse(
    val question: ContentLocalizationResponse,
    val answer: ContentLocalizationResponse? = null,
    val aiResponse: ContentLocalizationResponse? = null,
)

fun originalLocalization(
    sourceLanguage: String,
    requestedLanguage: String,
    viewMode: TranslationViewMode = TranslationViewMode.LOCALIZED,
    pending: Boolean = false,
): ContentLocalizationResponse {
    val normalizedSource = com.buddystudy.study.domain.QuestionLanguage.normalize(sourceLanguage)
    val normalizedRequested = com.buddystudy.study.domain.QuestionLanguage.normalize(requestedLanguage)
    val originalView = viewMode == TranslationViewMode.ORIGINAL
    return ContentLocalizationResponse(
        sourceLanguage = normalizedSource,
        requestedLanguage = normalizedRequested,
        displayLanguage = normalizedSource,
        translationState = when {
            originalView || normalizedSource == normalizedRequested -> TranslationState.ORIGINAL
            pending -> TranslationState.PENDING
            else -> TranslationState.FAILED
        },
        isTranslated = false,
        originalAvailable = false,
        translationReason = if (originalView) TranslationReason.ORIGINAL_VIEW else TranslationReason.EXPLICIT_TL,
    )
}

fun translatedLocalization(
    sourceLanguage: String,
    requestedLanguage: String,
): ContentLocalizationResponse = ContentLocalizationResponse(
    sourceLanguage = com.buddystudy.study.domain.QuestionLanguage.normalize(sourceLanguage),
    requestedLanguage = com.buddystudy.study.domain.QuestionLanguage.normalize(requestedLanguage),
    displayLanguage = com.buddystudy.study.domain.QuestionLanguage.normalize(requestedLanguage),
    translationState = TranslationState.TRANSLATED,
    isTranslated = true,
    originalAvailable = true,
    translationReason = TranslationReason.EXPLICIT_TL,
)
