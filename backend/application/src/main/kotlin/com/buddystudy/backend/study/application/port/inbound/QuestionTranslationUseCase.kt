package com.buddystudy.backend.study.application.port.inbound

import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent

interface ProcessQuestionTranslationUseCase {
    suspend fun process(event: QuestionGeneratedEvent)
}

data class QuestionTopicTranslationBackfillResult(
    val candidates: Int,
    val translated: Int,
    val failed: Int,
)

interface BackfillQuestionTopicsUseCase {
    suspend fun backfill(limit: Int): QuestionTopicTranslationBackfillResult
}
