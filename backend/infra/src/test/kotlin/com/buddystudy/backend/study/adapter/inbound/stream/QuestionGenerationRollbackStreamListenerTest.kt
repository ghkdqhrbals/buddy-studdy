package com.buddystudy.backend.study.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.study.application.model.QuestionGenerationRollbackRequestedEvent
import com.buddystudy.backend.study.application.service.QuestionGenerationRollbackWriteService
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QuestionGenerationRollbackStreamListenerTest {
    @Test
    fun `rollback and recovery use one durable consumer group`() {
        val consume = QuestionGenerationRollbackStreamListener::class.declaredFunctions
            .single { it.name == "consume" }
            .findAnnotation<StreamListener>()
        val recover = QuestionGenerationRollbackStreamListener::class.declaredFunctions
            .single { it.name == "recover" }
            .findAnnotation<StreamScheduler>()

        assertThat(consume).isNotNull
        assertThat(consume!!.topic).isEqualTo(RedisStreamTopic.STUDY_QUESTION_GENERATION_ROLLBACK_REQUESTED)
        assertThat(consume.eventType).isEqualTo(QuestionGenerationRollbackRequestedEvent.EVENT_TYPE)
        assertThat(consume.group).isEqualTo(QuestionGenerationRollbackWriteService.CONSUMER_GROUP)
        assertThat(consume.options).isEqualTo(StreamOptions.ACK)
        assertThat(recover).isNotNull
        assertThat(recover!!.topic).isEqualTo(consume.topic)
        assertThat(recover.eventType).isEqualTo(consume.eventType)
        assertThat(recover.group).isEqualTo(consume.group)
        assertThat(recover.options).isEqualTo(StreamOptions.ACK)
        assertThat(recover.minIdleTimeMs)
            .isEqualTo(QuestionGenerationRollbackWriteService.RECOVERY_MIN_IDLE_TIME_MILLIS)
    }
}
