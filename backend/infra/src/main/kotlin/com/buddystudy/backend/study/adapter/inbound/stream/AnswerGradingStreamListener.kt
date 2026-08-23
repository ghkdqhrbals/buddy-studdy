package com.buddystudy.backend.study.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.port.inbound.ProcessAnswerGradingUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AnswerGradingStreamListener(
    private val grading: ProcessAnswerGradingUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @StreamListener(
        topic = RedisStreamTopic.STUDY_ANSWER_GRADING_REQUESTED,
        group = GROUP,
        consumer = CONSUMER,
        eventType = EVENT_TYPE,
        payloadType = AnswerGradingRequestedEvent::class,
        batchSize = 10,
        blockTimeMs = 3_000,
        pollDelayMs = 250,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun consume(
        payload: AnswerGradingRequestedEvent,
        context: StreamMessageContext,
    ) {
        deliver(payload, context)
    }

    @StreamScheduler(
        topic = RedisStreamTopic.STUDY_ANSWER_GRADING_REQUESTED,
        group = GROUP,
        consumer = RECOVERY_CONSUMER,
        eventType = EVENT_TYPE,
        payloadType = AnswerGradingRequestedEvent::class,
        batchSize = 10,
        minIdleTimeMs = 300_000,
        fixedDelayMs = 30_000,
        initialDelayMs = 30_000,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun recover(
        payload: AnswerGradingRequestedEvent,
        context: StreamMessageContext,
    ) {
        deliver(payload, context)
    }

    internal suspend fun deliver(
        payload: AnswerGradingRequestedEvent,
        context: StreamMessageContext,
    ) {
        log.info(
            "answer_grading_started redisRecordId={} eventId={} recordId={} claimed={}",
            context.recordId,
            payload.eventId,
            payload.recordId,
            context.claimed,
        )
        grading.process(payload, context.streamKey)
    }

    private companion object {
        const val GROUP = "bs-backend-answer-grading"
        const val CONSUMER = "buddystudy-answer-grading"
        const val RECOVERY_CONSUMER = "buddystudy-answer-grading-recovery"
        const val EVENT_TYPE = "ANSWER_GRADING_REQUESTED"
    }
}
