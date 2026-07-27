package com.buddystudy.backend.study.application.model

import java.time.Instant

data class QuestionGeneratedEvent(
    val eventId: String,
    val questionId: Long,
    val userId: Long,
    val sourceLanguage: String,
    val generatedAt: Instant,
    val correlationId: String = eventId,
    val causationId: String? = null,
    val eventType: String = EVENT_TYPE,
    val eventVersion: Int = 1,
    val studyId: Long = 0,
    val topicId: Long = 0,
    val source: QuestionGenerationSource = QuestionGenerationSource.MANUAL,
    val occurredAt: Instant = generatedAt,
) {
    companion object {
        const val EVENT_TYPE = "QUESTION_GENERATED"
    }
}

data class TranslatedQuestionContent(
    val question: String,
    val hint: String?,
)
