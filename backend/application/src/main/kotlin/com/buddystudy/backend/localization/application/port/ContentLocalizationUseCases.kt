package com.buddystudy.backend.localization.application.port

import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.entity.QuestionEntity

interface RequestContentLocalizationUseCase {
    suspend fun requestRecord(question: QuestionEntity, targetLanguage: String)
    suspend fun requestComment(comment: QuestionCommentEntity, targetLanguage: String)
}

interface ProcessContentTranslationUseCase {
    suspend fun process(event: ContentTranslationRequestedEvent)
}
