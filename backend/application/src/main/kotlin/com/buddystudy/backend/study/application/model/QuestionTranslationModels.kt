package com.buddystudy.backend.study.application.model

import java.time.Instant

data class QuestionGeneratedEvent(
    val eventId: String,
    val questionId: Long,
    val userId: Long,
    val sourceLanguage: String,
    val generatedAt: Instant,
)

data class TranslatedQuestionContent(
    val question: String,
    val hint: String?,
)
