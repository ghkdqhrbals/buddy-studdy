package com.buddystudy.backend.community.adapter.outbound.stream

import com.buddystudy.backend.study.adapter.outbound.stream.BaseRedisStreamEvent
import java.time.Instant
import java.util.UUID
import com.buddystudy.common.application.model.QuestionStreamEventType

data class PublicQuestionViewedEvent(
    val questionId: Long,
    val userId: Long?,
    val translationState: String? = null,
    val translationLanguage: String? = null,
    val translationReason: String? = null,
    val requestId: String? = null,
    val questionSourceLanguage: String? = null,
    val questionDisplayLanguage: String? = null,
    val answerSourceLanguage: String? = null,
    val answerDisplayLanguage: String? = null,
    val aiResponseSourceLanguage: String? = null,
    val aiResponseDisplayLanguage: String? = null,
    val createdAt: Instant = Instant.now(),
    override val eventId: String = UUID.randomUUID().toString(),
) : BaseRedisStreamEvent(QuestionStreamEventType.CONTENT_VIEWED, eventId) {
    val minuteBucket: Long = createdAt.epochSecond / 60
}
