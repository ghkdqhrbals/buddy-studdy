package com.buddystuddy.backend.study.adapter.outbound.stream

import com.buddystuddy.common.application.model.QuestionStreamEventType
import java.time.Instant
import java.util.UUID

interface RedisStreamEvent {
    val eventId: String
    val eventType: QuestionStreamEventType
}

abstract class BaseRedisStreamEvent(
    override val eventType: QuestionStreamEventType,
    override val eventId: String = UUID.randomUUID().toString(),
) : RedisStreamEvent

data class QuestionPushRequestedEvent(
    val recordId: Long,
    val studyId: Long?,
    val deviceId: String,
    val userId: Long?,
    val question: String,
    val expectedAnswerHint: String?,
    val topic: String,
    val difficultyLevel: Int,
    val language: String,
    val sound: String?,
    val intervalMinutes: Int,
    val createdAt: Instant = Instant.now(),
    override val eventId: String = UUID.randomUUID().toString(),
) : BaseRedisStreamEvent(QuestionStreamEventType.QUESTION_PUSH_REQUESTED, eventId)
