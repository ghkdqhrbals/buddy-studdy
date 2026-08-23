package com.buddystudy.backend.notification.application.service

import com.buddystudy.backend.common.application.outbox.AfterCommitPort
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import org.springframework.stereotype.Service

@Service
class NotificationPublicationService(
    private val outbox: RedisEventOutboxAppendPort,
    private val afterCommit: AfterCommitPort,
    private val outboxPublisher: PublishOutboxUseCase,
) : PublishNotificationUseCase {
    override suspend fun publish(command: NotificationRequestCommand): Boolean {
        val outboxId = outbox.appendNotification(command)
        afterCommit.execute {
            outboxPublisher.publishNow(listOf(OutboxReference(OutboxType.DOMAIN_EVENT, outboxId)))
        }
        return true
    }
}
