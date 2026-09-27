package com.buddystudy.backend.notification.application.service

import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.notification.application.port.inbound.DispatchPushCampaignsUseCase
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.outbound.PushCampaignPersistencePort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PushCampaignDispatchService(
    private val campaigns: PushCampaignPersistencePort,
    private val outbox: RedisEventOutboxAppendPort,
) : DispatchPushCampaignsUseCase {
    @Transactional
    override suspend fun dispatchBatch(): Int {
        // The row locks and outbox writes share one transaction. A crash rolls both back;
        // deterministic event IDs also deduplicate downstream replay.
        val recipients = campaigns.pendingRecipientsForUpdate(100)
        for (recipient in recipients) {
            val now = Instant.now()
            outbox.appendNotification(NotificationRequestCommand(
                eventId = recipient.eventId,
                userId = recipient.userId,
                type = "MARKETING",
                title = recipient.title,
                body = recipient.body,
                deepLink = recipient.deepLink,
                shouldPush = true,
            ), now)
            campaigns.markEnqueued(recipient.campaignId, recipient.userId, now)
        }
        // Existing event-outbox-dispatch publishes these durable records and retries outages.
        return recipients.size
    }
}
