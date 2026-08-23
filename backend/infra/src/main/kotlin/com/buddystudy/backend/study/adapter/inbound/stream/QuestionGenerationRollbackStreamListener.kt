package com.buddystudy.backend.study.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.study.application.model.QuestionGenerationRollbackRequestedEvent
import com.buddystudy.backend.study.application.port.inbound.ProcessQuestionGenerationRollbackUseCase
import com.buddystudy.backend.study.application.service.QuestionGenerationRollbackWriteService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class QuestionGenerationRollbackStreamListener(
    private val rollback: ProcessQuestionGenerationRollbackUseCase,
) {
    @StreamListener(
        topic = RedisStreamTopic.STUDY_QUESTION_GENERATION_ROLLBACK_REQUESTED,
        group = QuestionGenerationRollbackWriteService.CONSUMER_GROUP,
        consumer = CONSUMER,
        eventType = QuestionGenerationRollbackRequestedEvent.EVENT_TYPE,
        payloadType = QuestionGenerationRollbackRequestedEvent::class,
        batchSize = 10,
        blockTimeMs = 3_000,
        pollDelayMs = 250,
        concurrency = 2,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun consume(
        payload: QuestionGenerationRollbackRequestedEvent,
        context: StreamMessageContext,
    ) {
        rollback.process(payload, context.streamKey)
    }

    @StreamScheduler(
        topic = RedisStreamTopic.STUDY_QUESTION_GENERATION_ROLLBACK_REQUESTED,
        group = QuestionGenerationRollbackWriteService.CONSUMER_GROUP,
        consumer = RECOVERY_CONSUMER,
        eventType = QuestionGenerationRollbackRequestedEvent.EVENT_TYPE,
        payloadType = QuestionGenerationRollbackRequestedEvent::class,
        batchSize = 10,
        minIdleTimeMs = QuestionGenerationRollbackWriteService.RECOVERY_MIN_IDLE_TIME_MILLIS,
        fixedDelayMs = 10_000,
        initialDelayMs = 10_000,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun recover(
        payload: QuestionGenerationRollbackRequestedEvent,
        context: StreamMessageContext,
    ) {
        rollback.process(payload, context.streamKey)
    }

    private companion object {
        const val CONSUMER = "buddystudy-question-generation-rollback"
        const val RECOVERY_CONSUMER = "buddystudy-question-generation-rollback-recovery"
    }
}
