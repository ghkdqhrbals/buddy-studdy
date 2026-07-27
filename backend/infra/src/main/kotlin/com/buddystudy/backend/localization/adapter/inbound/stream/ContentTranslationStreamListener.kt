package com.buddystudy.backend.localization.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.localization.application.model.ContentTranslationRequestedEvent
import com.buddystudy.backend.localization.application.port.ProcessContentTranslationUseCase
import com.buddystudy.backend.localization.application.service.ContentTranslationProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ContentTranslationStreamListener(
    private val translations: ProcessContentTranslationUseCase,
) {
    @StreamListener(
        topic = RedisStreamTopic.CONTENT_TRANSLATION,
        group = ContentTranslationProcessor.CONSUMER_GROUP,
        consumer = "buddystudy-content-translation",
        eventType = ContentTranslationRequestedEvent.EVENT_TYPE,
        payloadType = ContentTranslationRequestedEvent::class,
        batchSize = 10,
        blockTimeMs = 3_000,
        pollDelayMs = 250,
        concurrency = 2,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun consume(payload: ContentTranslationRequestedEvent, context: StreamMessageContext) {
        translations.process(payload)
    }

    @StreamScheduler(
        topic = RedisStreamTopic.CONTENT_TRANSLATION,
        group = ContentTranslationProcessor.CONSUMER_GROUP,
        consumer = "buddystudy-content-translation-recovery",
        eventType = ContentTranslationRequestedEvent.EVENT_TYPE,
        payloadType = ContentTranslationRequestedEvent::class,
        batchSize = 10,
        minIdleTimeMs = ContentTranslationProcessor.RECOVERY_MIN_IDLE_TIME_MILLIS,
        fixedDelayMs = 10_000,
        initialDelayMs = 10_000,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun recover(payload: ContentTranslationRequestedEvent, context: StreamMessageContext) {
        translations.process(payload)
    }
}
