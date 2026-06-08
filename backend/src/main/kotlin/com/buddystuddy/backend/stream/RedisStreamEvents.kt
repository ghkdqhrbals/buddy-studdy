package com.buddystuddy.backend.stream

import java.time.Instant
import java.util.UUID

interface RedisStreamEvent {
    val eventId: String
    val eventType: QuestionStreamEventType

    fun toMap(): Map<String, Any?>
}

@Suppress("NOTHING_TO_INLINE")
inline fun <T : RedisStreamEvent> T.toStreamMap(): Map<String, Any?> = toMap()

enum class QuestionStreamEventType {
    QUESTION_PUSH_REQUESTED,
    CONTENT_VIEWED,
    QUESTION_LIKED,
    QUESTION_UNLIKED,
    QUESTION_COMMENTED,
    QUESTION_COMMENT_DELETED,
}

data class QuestionPushRequestedEvent(
    val fields: Map<String, Any?>,
    override val eventId: String = UUID.randomUUID().toString(),
) : RedisStreamEvent {
    override val eventType: QuestionStreamEventType = QuestionStreamEventType.QUESTION_PUSH_REQUESTED

    override fun toMap(): Map<String, Any?> =
        fields + eventFields()
}

data class QuestionViewedEvent(
    val questionId: Long,
    val userId: Long?,
    val viewedAt: Instant = Instant.now(),
    override val eventId: String = UUID.randomUUID().toString(),
) : RedisStreamEvent {
    override val eventType: QuestionStreamEventType = QuestionStreamEventType.CONTENT_VIEWED

    override fun toMap(): Map<String, Any?> = eventFields() + mapOf(
        "questionId" to questionId,
        "userId" to userId,
        "minuteBucket" to viewedAt.epochSecond / 60,
        "createdAt" to viewedAt.toString(),
    )
}

data class QuestionActionEvent(
    val questionId: Long,
    override val eventType: QuestionStreamEventType,
    val userId: Long?,
    val createdAt: Instant = Instant.now(),
    override val eventId: String = UUID.randomUUID().toString(),
) : RedisStreamEvent {
    init {
        require(eventType != QuestionStreamEventType.QUESTION_PUSH_REQUESTED)
        require(eventType != QuestionStreamEventType.CONTENT_VIEWED)
    }

    override fun toMap(): Map<String, Any?> = eventFields() + mapOf(
        "questionId" to questionId,
        "userId" to userId,
        "createdAt" to createdAt.toString(),
    )
}

private fun RedisStreamEvent.eventFields(): Map<String, Any?> = mapOf(
    "eventId" to eventId,
    "eventType" to eventType.name,
)
