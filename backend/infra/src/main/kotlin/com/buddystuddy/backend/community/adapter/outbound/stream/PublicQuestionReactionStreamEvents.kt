package com.buddystuddy.backend.community.adapter.outbound.stream

import com.buddystuddy.backend.study.adapter.outbound.stream.BaseRedisStreamEvent
import com.buddystuddy.common.application.model.QuestionStreamEventType
import java.time.Instant
import java.util.UUID

data class PublicQuestionViewedEvent(
    val questionId: Long,
    val userId: Long?,
    val createdAt: Instant = Instant.now(),
    override val eventId: String = UUID.randomUUID().toString(),
) : BaseRedisStreamEvent(QuestionStreamEventType.CONTENT_VIEWED, eventId) {
    val minuteBucket: Long = createdAt.epochSecond / 60
}

data class PublicQuestionActionEvent(
    val questionId: Long,
    override val eventType: QuestionStreamEventType,
    val userId: Long?,
    val createdAt: Instant = Instant.now(),
    override val eventId: String = UUID.randomUUID().toString(),
) : BaseRedisStreamEvent(eventType, eventId) {
    init {
        require(eventType != QuestionStreamEventType.QUESTION_PUSH_REQUESTED)
        require(eventType != QuestionStreamEventType.CONTENT_VIEWED)
    }
}
