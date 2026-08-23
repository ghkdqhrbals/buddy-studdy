package com.buddystudy.backend.community.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.community.application.model.NativeAdvertisementViewedEvent
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementPort
import com.buddystudy.backend.study.application.port.outbound.StreamInboxPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class NativeAdvertisementViewStreamListener(
    private val handler: NativeAdvertisementViewStreamEventHandler,
) {
    @StreamListener(
        topic = RedisStreamTopic.COMMUNITY_NATIVE_AD_VIEW,
        group = GROUP,
        consumer = "buddystudy-native-ad-view",
        eventType = EVENT_TYPE,
        payloadType = NativeAdvertisementViewedEvent::class,
        batchSize = 100,
        blockTimeMs = 3_000,
        pollDelayMs = 250,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun consume(payload: NativeAdvertisementViewedEvent, context: StreamMessageContext) {
        handler.process(payload, context.streamKey)
    }

    @StreamScheduler(
        topic = RedisStreamTopic.COMMUNITY_NATIVE_AD_VIEW,
        group = GROUP,
        consumer = "buddystudy-native-ad-view-recovery",
        eventType = EVENT_TYPE,
        payloadType = NativeAdvertisementViewedEvent::class,
        batchSize = 100,
        minIdleTimeMs = 60_000,
        fixedDelayMs = 30_000,
        initialDelayMs = 30_000,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun recover(payload: NativeAdvertisementViewedEvent, context: StreamMessageContext) {
        handler.process(payload, context.streamKey)
    }
}

@Component
class NativeAdvertisementViewStreamEventHandler(
    private val nativeAdvertisements: NativeAdvertisementPort,
    private val inbox: StreamInboxPort,
) {
    @Transactional
    suspend fun process(event: NativeAdvertisementViewedEvent, streamKey: String) {
        val now = Instant.now()
        val claim = inbox.claim(
            eventId = event.eventId,
            consumerGroup = GROUP,
            correlationId = event.selectionId,
            leaseDuration = LEASE,
            now = now,
            streamKey = streamKey,
        ) ?: return
        nativeAdvertisements.markView(
            selectionId = event.selectionId,
            userId = event.userId,
            deviceId = event.deviceId,
            at = event.occurredAt,
        )
        check(inbox.markSucceeded(claim, now)) {
            "Native advertisement view Inbox claim was lost before completion."
        }
    }
}

private const val GROUP = "bs-backend-native-ad-view"
private const val EVENT_TYPE = "NATIVE_AD_VIEWED"
private val LEASE = Duration.ofMinutes(5)
