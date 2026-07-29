package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.common.application.outbox.ClaimedRedisOutboxEvent
import com.buddystudy.backend.common.application.outbox.DomainEventPublishPort
import com.buddystudy.backend.common.application.outbox.RedisOutboxEventType
import org.springframework.stereotype.Component

@Component
class RedisDomainEventPublisher(
    private val streams: RedisStreamPublishOperations,
) : DomainEventPublishPort {
    override suspend fun publish(event: ClaimedRedisOutboxEvent): String =
        streams.publish(
            event.topic(),
            mapOf(
                "eventId" to event.eventId,
                "eventType" to event.eventType.name,
                "payload" to event.payloadJson,
            ),
        ).recordId

    private fun ClaimedRedisOutboxEvent.topic(): RedisStreamTopic =
        when (eventType) {
            RedisOutboxEventType.NOTIFICATION_REQUESTED -> RedisStreamTopic.NOTIFICATION_MESSAGE_REQUESTED
            RedisOutboxEventType.ACCOUNT_WITHDRAWN -> RedisStreamTopic.IDENTITY_ACCOUNT_WITHDRAWN
            RedisOutboxEventType.ANSWER_GRADING_REQUESTED -> RedisStreamTopic.STUDY_ANSWER_GRADING_REQUESTED
            RedisOutboxEventType.QUESTION_GENERATION_REQUESTED ->
                RedisStreamTopic.STUDY_QUESTION_GENERATION_REQUESTED
            RedisOutboxEventType.QUESTION_GENERATED -> RedisStreamTopic.STUDY_QUESTION_GENERATED
            RedisOutboxEventType.CONTENT_TRANSLATION_REQUESTED ->
                RedisStreamTopic.LOCALIZATION_CONTENT_TRANSLATION_REQUESTED
        }
}
