package com.buddystudy.backend.study.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent
import com.buddystudy.backend.study.application.service.QuestionGenerationExecutionWriteService
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QuestionGenerationStreamListenerTest {
    @Test
    fun `generation and recovery use one group and recovery waits beyond the Inbox lease`() {
        val consume = QuestionGenerationStreamListener::class.declaredFunctions
            .single { it.name == "consume" }
            .findAnnotation<StreamListener>()
        val recover = QuestionGenerationStreamListener::class.declaredFunctions
            .single { it.name == "recover" }
            .findAnnotation<StreamScheduler>()

        assertThat(consume).isNotNull
        assertThat(consume!!.topic).isEqualTo(RedisStreamTopic.STUDY_QUESTION_GENERATION_REQUESTED)
        assertThat(consume.eventType).isEqualTo(QuestionGenerationRequestedEvent.EVENT_TYPE)
        assertThat(consume.group).isEqualTo(QuestionGenerationExecutionWriteService.CONSUMER_GROUP)
        assertThat(consume.options).isEqualTo(StreamOptions.ACK)
        assertThat(recover).isNotNull
        assertThat(recover!!.topic).isEqualTo(consume.topic)
        assertThat(recover.eventType).isEqualTo(consume.eventType)
        assertThat(recover.group).isEqualTo(consume.group)
        assertThat(recover.options).isEqualTo(StreamOptions.ACK)
        assertThat(recover.minIdleTimeMs)
            .isEqualTo(QuestionGenerationExecutionWriteService.RECOVERY_MIN_IDLE_TIME_MILLIS)
    }
}
