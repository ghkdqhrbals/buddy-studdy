package com.buddystudy.backend.community.application.model

import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.backend.profile.application.model.UserProfileResponse
import com.buddystudy.backend.study.application.model.TranslationViewMode
import com.buddystudy.backend.study.application.model.originalLocalization
import com.buddystudy.backend.study.application.model.authorOriginalLocalization
import com.buddystudy.backend.study.application.model.translatedLocalization

fun QuestionCommentEntity.toResponse(
    author: UserProfileResponse,
    requestedLanguage: String = sourceLanguage.databaseValue,
    viewMode: TranslationViewMode = TranslationViewMode.LOCALIZED,
    displayLanguage: String = sourceLanguage.databaseValue,
    translationPending: Boolean = true,
    authorOriginal: Boolean = false,
) = CommunityCommentResponse(
    id.toString(),
    questionId.toString(),
    body,
    createdAt,
    author,
    if (authorOriginal) {
        authorOriginalLocalization(sourceLanguage.databaseValue, requestedLanguage)
    } else if (
        viewMode == TranslationViewMode.LOCALIZED &&
        com.buddystudy.study.domain.QuestionLanguage.normalize(displayLanguage) ==
        com.buddystudy.study.domain.QuestionLanguage.normalize(requestedLanguage) &&
        com.buddystudy.study.domain.QuestionLanguage.normalize(sourceLanguage.databaseValue) !=
        com.buddystudy.study.domain.QuestionLanguage.normalize(requestedLanguage)
    ) {
        translatedLocalization(sourceLanguage.databaseValue, requestedLanguage)
    } else {
        originalLocalization(
            sourceLanguage = sourceLanguage.databaseValue,
            requestedLanguage = requestedLanguage,
            viewMode = viewMode,
            pending = viewMode == TranslationViewMode.LOCALIZED &&
                com.buddystudy.study.domain.QuestionLanguage.normalize(sourceLanguage.databaseValue) !=
                com.buddystudy.study.domain.QuestionLanguage.normalize(requestedLanguage) &&
                translationPending,
        )
    },
)
