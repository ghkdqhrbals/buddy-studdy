package com.buddystudy.backend.profile.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.profile.application.model.AccountWithdrawnEvent
import com.buddystudy.backend.profile.application.port.inbound.AccountWithdrawalCleanupUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AccountWithdrawalStreamListener(
    private val cleanup: AccountWithdrawalCleanupUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @StreamListener(
        topic = RedisStreamTopic.DOMAIN_EVENTS,
        group = GROUP,
        consumer = CONSUMER,
        eventType = EVENT_TYPE,
        payloadType = AccountWithdrawnEvent::class,
        batchSize = 50,
        blockTimeMs = 3_000,
        pollDelayMs = 1_000,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun consume(
        payload: AccountWithdrawnEvent,
        context: StreamMessageContext,
    ) {
        deliver(payload, context)
    }

    @StreamScheduler(
        topic = RedisStreamTopic.DOMAIN_EVENTS,
        group = GROUP,
        consumer = RECOVERY_CONSUMER,
        eventType = EVENT_TYPE,
        payloadType = AccountWithdrawnEvent::class,
        batchSize = 50,
        minIdleTimeMs = 300_000,
        fixedDelayMs = 30_000,
        initialDelayMs = 30_000,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun recover(
        payload: AccountWithdrawnEvent,
        context: StreamMessageContext,
    ) {
        deliver(payload, context)
    }

    internal suspend fun deliver(
        payload: AccountWithdrawnEvent,
        context: StreamMessageContext,
    ) {
        log.info(
            "account_withdrawal_cleanup_started redisRecordId={} eventId={} userId={} claimed={}",
            context.recordId,
            payload.eventId,
            payload.userId,
            context.claimed,
        )
        cleanup.cleanup(payload)
    }

    private companion object {
        const val GROUP = "bs-backend-account-withdrawal"
        const val CONSUMER = "buddystudy-account-withdrawal"
        const val RECOVERY_CONSUMER = "buddystudy-account-withdrawal-recovery"
        const val EVENT_TYPE = "ACCOUNT_WITHDRAWN"
    }
}
