package com.buddystudy.backend.study.adapter.outbound.stream

import com.buddystudy.common.application.model.QuestionStreamEventType
import com.fasterxml.jackson.annotation.JsonInclude
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
    val notificationId: Long? = null,
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
    val title: String? = null,
    val body: String? = null,
    val deepLink: String? = null,
    val createdAt: Instant = Instant.now(),
    override val eventId: String = UUID.randomUUID().toString(),
) : BaseRedisStreamEvent(QuestionStreamEventType.QUESTION_PUSH_REQUESTED, eventId)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class QuestionPushRequestedPayload(
    val recordId: Long,
    val notificationId: Long? = null,
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
    val title: String?,
    val body: String?,
    val deepLink: String?,
    val createdAt: Instant,
)

fun QuestionPushRequestedEvent.toPayload(): QuestionPushRequestedPayload =
    QuestionPushRequestedPayload(
        recordId = recordId,
        notificationId = notificationId,
        studyId = studyId,
        deviceId = deviceId,
        userId = userId,
        question = question,
        expectedAnswerHint = expectedAnswerHint,
        topic = topic,
        difficultyLevel = difficultyLevel,
        language = language,
        sound = sound,
        intervalMinutes = intervalMinutes,
        title = title,
        body = body,
        deepLink = deepLink,
        createdAt = createdAt,
    )
