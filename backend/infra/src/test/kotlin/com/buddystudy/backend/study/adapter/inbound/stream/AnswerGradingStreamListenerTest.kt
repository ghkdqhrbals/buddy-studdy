package com.buddystudy.backend.study.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.port.inbound.ProcessAnswerGradingUseCase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation

class AnswerGradingStreamListenerTest {
    @Test
    fun `grading listener uses typed domain event and ACK`() {
        val annotation = AnswerGradingStreamListener::class.declaredFunctions
            .single { it.name == "consume" }
            .findAnnotation<StreamListener>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.STUDY_ANSWER_GRADING_REQUESTED)
        assertThat(annotation.group).isEqualTo("bs-backend-answer-grading")
        assertThat(annotation.eventType).isEqualTo("ANSWER_GRADING_REQUESTED")
        assertThat(annotation.payloadType).isEqualTo(AnswerGradingRequestedEvent::class)
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK)
    }

    @Test
    fun `grading recovery uses the same event contract`() {
        val annotation = AnswerGradingStreamListener::class.declaredFunctions
            .single { it.name == "recover" }
            .findAnnotation<StreamScheduler>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.STUDY_ANSWER_GRADING_REQUESTED)
        assertThat(annotation.group).isEqualTo("bs-backend-answer-grading")
        assertThat(annotation.eventType).isEqualTo("ANSWER_GRADING_REQUESTED")
        assertThat(annotation.payloadType).isEqualTo(AnswerGradingRequestedEvent::class)
        assertThat(annotation.minIdleTimeMs).isEqualTo(300_000)
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK)
    }

    @Test
    fun `delivery delegates one event to the grading use case`() = runBlocking {
        val processed = mutableListOf<Pair<AnswerGradingRequestedEvent, String>>()
        val listener = AnswerGradingStreamListener(
            grading = object : ProcessAnswerGradingUseCase {
                override suspend fun process(event: AnswerGradingRequestedEvent, streamKey: String) {
                    processed += event to streamKey
                }
            },
        )
        val event = AnswerGradingRequestedEvent(
            eventId = "answer-grading-requested:request-1",
            requestId = "request-1",
            recordId = 10,
            userId = 20,
            requestedAt = Instant.parse("2026-07-27T00:00:00Z"),
        )

        listener.deliver(
            payload = event,
            context = StreamMessageContext(
                streamKey = "buddystudy-domain-events-v1",
                recordId = "1-0",
                eventId = event.eventId,
                eventType = "ANSWER_GRADING_REQUESTED",
                fields = emptyMap(),
                claimed = false,
            ),
        )

        assertThat(processed).containsExactly(event to "buddystudy-domain-events-v1")
    }
}
