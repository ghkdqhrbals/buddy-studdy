package com.buddystudy.backend.community.application.model

import com.buddystudy.community.domain.PublicQuestionProjection
import com.buddystudy.backend.profile.application.model.UserProfileResponse
import com.buddystudy.backend.study.application.model.GradingResultResponse
import com.buddystudy.backend.study.application.model.RecordLocalizationResponse
import com.buddystudy.backend.study.application.model.originalLocalization
import com.buddystudy.backend.study.application.model.translatedLocalization
import com.buddystudy.backend.study.application.model.TranslationViewMode

fun PublicQuestionProjection.toCommunityQuestionResponse(
    requestedLanguage: String = questionSourceLanguage,
    viewMode: TranslationViewMode = TranslationViewMode.LOCALIZED,
    questionDisplayLanguage: String = questionSourceLanguage,
    answerDisplayLanguage: String = answerSourceLanguage ?: questionSourceLanguage,
    aiResponseDisplayLanguage: String = aiResponseSourceLanguage ?: questionSourceLanguage,
    questionTranslationPending: Boolean = true,
    answerTranslationPending: Boolean = true,
    aiResponseTranslationPending: Boolean = true,
) = CommunityQuestionResponse(
    id = id,
    question = question,
    answer = answer,
    gradingResult = score?.let {
        GradingResultResponse(it, correct ?: (it >= 70), feedback ?: "", explanation ?: "")
    },
    topic = topic,
    difficultyLevel = difficultyLevel,
    status = status,
    source = source,
    createdAt = createdAt,
    answeredAt = answeredAt,
    author = author?.let {
        UserProfileResponse(
            id = it.id,
            displayName = it.displayName,
            bio = it.bio,
            avatarUrl = it.avatarUrl,
            avatarSymbolName = it.avatarSymbolName,
            avatarColorSeed = it.avatarColorSeed,
        )
    },
    likeCount = likeCount,
    commentCount = commentCount,
    viewCount = viewCount,
    isLikedByMe = isLikedByMe,
    localization = RecordLocalizationResponse(
        question = locale(
            questionSourceLanguage,
            requestedLanguage,
            questionDisplayLanguage,
            viewMode,
            questionTranslationPending,
        ),
        answer = answer?.let {
            locale(
                answerSourceLanguage ?: questionSourceLanguage,
                requestedLanguage,
                answerDisplayLanguage,
                viewMode,
                answerTranslationPending,
            )
        },
        aiResponse = score?.let {
            locale(
                aiResponseSourceLanguage ?: questionSourceLanguage,
                requestedLanguage,
                aiResponseDisplayLanguage,
                viewMode,
                aiResponseTranslationPending,
            )
        },
    ),
)

private fun locale(
    sourceLanguage: String,
    requestedLanguage: String,
    displayLanguage: String,
    viewMode: TranslationViewMode,
    pending: Boolean,
) = when {
    viewMode == TranslationViewMode.ORIGINAL || sourceLanguage == requestedLanguage ->
        originalLocalization(sourceLanguage, requestedLanguage, viewMode)
    displayLanguage == requestedLanguage -> translatedLocalization(sourceLanguage, requestedLanguage)
    else -> originalLocalization(sourceLanguage, requestedLanguage, viewMode, pending = pending)
}
