package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.common.application.outbox.ClaimedRedisOutboxEvent
import com.buddystudy.backend.common.application.outbox.DomainEventPublishPort
import org.springframework.stereotype.Component

@Component
class RedisDomainEventPublisher(
    private val streams: RedisStreamPublishOperations,
) : DomainEventPublishPort {
    override suspend fun publish(event: ClaimedRedisOutboxEvent): String =
        streams.publish(
            RedisStreamTopic.DOMAIN_EVENTS,
            mapOf(
                "eventId" to event.eventId,
                "eventType" to event.eventType.name,
                "payload" to event.payloadJson,
            ),
        ).recordId
}
