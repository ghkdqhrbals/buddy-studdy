package com.buddystudy.backend.study.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.study.adapter.outbound.stream.QuestionPushRequestedPayload
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PushStreamManagerAnnotationTest {
    @Test
    fun `push listener declares topic object group consumer and batch settings`() {
        val annotation = PushStreamManager::class.declaredFunctions
            .single { it.name == "consumePush" }
            .findAnnotation<StreamListener>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.DOMAIN_EVENTS)
        assertThat(annotation.payloadType).isEqualTo(QuestionPushRequestedPayload::class)
        assertThat(annotation.group).isEqualTo("bs-backend-push")
        assertThat(annotation.consumer).isEqualTo("buddystudy-push")
        assertThat(annotation.eventType).isEqualTo("QUESTION_PUSH_REQUESTED")
        assertThat(annotation.batchSize).isEqualTo(50)
        assertThat(annotation.concurrencyProperty).isEqualTo("buddystudy.streams.push-consumer-concurrency")
    }

    @Test
    fun `push recovery declares idle autoclaim settings`() {
        val annotation = PushStreamManager::class.declaredFunctions
            .single { it.name == "recoverIdlePush" }
            .findAnnotation<StreamScheduler>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.DOMAIN_EVENTS)
        assertThat(annotation.payloadType).isEqualTo(QuestionPushRequestedPayload::class)
        assertThat(annotation.group).isEqualTo("bs-backend-push")
        assertThat(annotation.consumer).isEqualTo("buddystudy-push-recovery")
        assertThat(annotation.minIdleTimeMs).isEqualTo(300_000)
        assertThat(annotation.batchSize).isEqualTo(50)
        assertThat(annotation.fixedDelayMs).isEqualTo(30_000)
    }
}
