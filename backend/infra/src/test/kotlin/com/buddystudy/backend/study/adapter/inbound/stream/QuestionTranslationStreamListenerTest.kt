package com.buddystudy.backend.study.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QuestionTranslationStreamListenerTest {
    @Test
    fun `uses a dedicated consumer group for generated question translation`() {
        val consume = QuestionTranslationStreamListener::class.declaredFunctions
            .single { it.name == "consume" }
            .findAnnotation<StreamListener>()
        val recover = QuestionTranslationStreamListener::class.declaredFunctions
            .single { it.name == "recover" }
            .findAnnotation<StreamScheduler>()

        assertThat(consume).isNotNull
        assertThat(consume!!.topic).isEqualTo(RedisStreamTopic.QUESTION_GENERATED)
        assertThat(consume.eventType).isEqualTo("QUESTION_GENERATED")
        assertThat(consume.group).isEqualTo("bs-backend-question-translation")
        assertThat(recover).isNotNull
        assertThat(recover!!.topic).isEqualTo(RedisStreamTopic.QUESTION_GENERATED)
        assertThat(recover.eventType).isEqualTo("QUESTION_GENERATED")
        assertThat(recover.group).isEqualTo(consume.group)
    }
}
