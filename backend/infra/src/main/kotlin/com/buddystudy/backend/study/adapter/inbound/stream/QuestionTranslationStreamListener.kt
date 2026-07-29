package com.buddystudy.backend.study.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.port.inbound.ProcessQuestionTranslationUseCase
import com.buddystudy.backend.study.application.service.QuestionTranslationExecutionWriteService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class QuestionTranslationStreamListener(
    private val translations: ProcessQuestionTranslationUseCase,
) {
    @StreamListener(
        topic = RedisStreamTopic.STUDY_QUESTION_GENERATED,
        legacyTopic = RedisStreamTopic.LEGACY_QUESTION_GENERATED,
        group = QuestionTranslationExecutionWriteService.CONSUMER_GROUP,
        consumer = CONSUMER,
        eventType = EVENT_TYPE,
        payloadType = QuestionGeneratedEvent::class,
        batchSize = 10,
        blockTimeMs = 3_000,
        pollDelayMs = 250,
        concurrency = 2,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun consume(payload: QuestionGeneratedEvent, context: StreamMessageContext) {
        translations.process(payload, context.streamKey)
    }

    @StreamScheduler(
        topic = RedisStreamTopic.STUDY_QUESTION_GENERATED,
        legacyTopic = RedisStreamTopic.LEGACY_QUESTION_GENERATED,
        group = QuestionTranslationExecutionWriteService.CONSUMER_GROUP,
        consumer = RECOVERY_CONSUMER,
        eventType = EVENT_TYPE,
        payloadType = QuestionGeneratedEvent::class,
        batchSize = 10,
        minIdleTimeMs = QuestionTranslationExecutionWriteService.RECOVERY_MIN_IDLE_TIME_MILLIS,
        fixedDelayMs = 10_000,
        initialDelayMs = 10_000,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun recover(payload: QuestionGeneratedEvent, context: StreamMessageContext) {
        translations.process(payload, context.streamKey)
    }

    private companion object {
        const val CONSUMER = "buddystudy-question-translation"
        const val RECOVERY_CONSUMER = "buddystudy-question-translation-recovery"
        const val EVENT_TYPE = "QUESTION_GENERATED"
    }
}
