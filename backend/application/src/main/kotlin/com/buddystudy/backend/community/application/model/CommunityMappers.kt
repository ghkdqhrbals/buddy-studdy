package com.buddystudy.backend.community.application.model

import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.backend.profile.application.model.UserProfileResponse
import com.buddystudy.backend.study.application.model.TranslationViewMode
import com.buddystudy.backend.study.application.model.originalLocalization
import com.buddystudy.backend.study.application.model.translatedLocalization

fun QuestionCommentEntity.toResponse(
    author: UserProfileResponse,
    requestedLanguage: String = sourceLanguage,
    viewMode: TranslationViewMode = TranslationViewMode.LOCALIZED,
    displayLanguage: String = sourceLanguage,
    translationPending: Boolean = true,
) = CommunityCommentResponse(
    id.toString(),
    questionId.toString(),
    body,
    createdAt,
    author,
    if (
        viewMode == TranslationViewMode.LOCALIZED &&
        com.buddystudy.study.domain.QuestionLanguage.normalize(displayLanguage) ==
        com.buddystudy.study.domain.QuestionLanguage.normalize(requestedLanguage) &&
        com.buddystudy.study.domain.QuestionLanguage.normalize(sourceLanguage) !=
        com.buddystudy.study.domain.QuestionLanguage.normalize(requestedLanguage)
    ) {
        translatedLocalization(sourceLanguage, requestedLanguage)
    } else {
        originalLocalization(
            sourceLanguage = sourceLanguage,
            requestedLanguage = requestedLanguage,
            viewMode = viewMode,
            pending = viewMode == TranslationViewMode.LOCALIZED &&
                com.buddystudy.study.domain.QuestionLanguage.normalize(sourceLanguage) !=
                com.buddystudy.study.domain.QuestionLanguage.normalize(requestedLanguage) &&
                translationPending,
        )
    },
)
