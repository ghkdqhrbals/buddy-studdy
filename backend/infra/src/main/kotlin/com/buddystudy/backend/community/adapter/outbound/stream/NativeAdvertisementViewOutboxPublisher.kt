package com.buddystudy.backend.community.adapter.outbound.stream

import com.buddystudy.backend.common.application.outbox.AfterCommitPort
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.community.application.model.NativeAdvertisementViewedEvent
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementViewPublishPort
import org.springframework.stereotype.Component

@Component
class NativeAdvertisementViewOutboxPublisher(
    private val outbox: RedisEventOutboxAppendPort,
    private val afterCommit: AfterCommitPort,
    private val publisher: PublishOutboxUseCase,
) : NativeAdvertisementViewPublishPort {
    override suspend fun publish(event: NativeAdvertisementViewedEvent): Boolean {
        val outboxId = outbox.appendNativeAdvertisementViewed(event, event.occurredAt)
        afterCommit.execute {
            publisher.publishNow(listOf(OutboxReference(OutboxType.DOMAIN_EVENT, outboxId)))
        }
        return true
    }
}
