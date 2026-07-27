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
            RedisOutboxEventType.QUESTION_GENERATION_REQUESTED -> RedisStreamTopic.QUESTION_GENERATION
            RedisOutboxEventType.QUESTION_GENERATED -> RedisStreamTopic.QUESTION_GENERATED
            else -> RedisStreamTopic.DOMAIN_EVENTS
        }
}
