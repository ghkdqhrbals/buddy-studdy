package com.buddystuddy.backend.community.adapter.outbound.stream

import com.buddystuddy.backend.study.adapter.outbound.stream.BaseRedisStreamEvent
import java.time.Instant
import java.util.UUID
import com.buddystuddy.common.application.model.QuestionStreamEventType

data class PublicQuestionViewedEvent(
    val questionId: Long,
    val userId: Long?,
    val createdAt: Instant = Instant.now(),
    override val eventId: String = UUID.randomUUID().toString(),
) : BaseRedisStreamEvent(QuestionStreamEventType.CONTENT_VIEWED, eventId) {
    val minuteBucket: Long = createdAt.epochSecond / 60
}
