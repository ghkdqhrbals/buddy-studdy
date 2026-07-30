package com.buddystudy.backend.common.adapter.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.community.adapter.inbound.stream.QuestionStatsStreamListener
import com.buddystudy.backend.localization.adapter.inbound.stream.ContentTranslationStreamListener
import com.buddystudy.backend.notification.adapter.inbound.stream.NotificationStreamListener
import com.buddystudy.backend.profile.adapter.inbound.stream.AccountWithdrawalStreamListener
import com.buddystudy.backend.study.adapter.inbound.stream.AnswerGradingStreamListener
import com.buddystudy.backend.study.adapter.inbound.stream.QuestionGenerationStreamListener
import com.buddystudy.backend.study.adapter.inbound.stream.QuestionTranslationStreamListener
import com.buddystudy.backend.study.adapter.stream.PushStreamManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisStreamRecoveryCoverageTest {
    @Test
    fun `every active stream listener has a matching recovery consumer in the same group`() {
        val listenerClasses = listOf(
            QuestionStatsStreamListener::class.java,
            ContentTranslationStreamListener::class.java,
            NotificationStreamListener::class.java,
            AccountWithdrawalStreamListener::class.java,
            AnswerGradingStreamListener::class.java,
            QuestionGenerationStreamListener::class.java,
            QuestionTranslationStreamListener::class.java,
            PushStreamManager::class.java,
        )
        val listeners = listenerClasses
            .flatMap { it.declaredMethods.toList() }
            .mapNotNull { it.getAnnotation(StreamListener::class.java) }
        val recoveries = listenerClasses
            .flatMap { it.declaredMethods.toList() }
            .mapNotNull { it.getAnnotation(StreamScheduler::class.java) }

        assertThat(listeners.map { it.topic }.toSet())
            .containsExactlyInAnyOrderElementsOf(RedisStreamTopic.entries)
        assertThat(recoveries.map { it.topic }.toSet())
            .containsExactlyInAnyOrderElementsOf(RedisStreamTopic.entries)
        listeners.forEach { listener ->
            assertThat(recoveries)
                .anySatisfy { recovery ->
                    assertThat(recovery.topic).isEqualTo(listener.topic)
                    assertThat(recovery.group).isEqualTo(listener.group)
                    assertThat(recovery.consumer).endsWith("-recovery")
                }
        }
    }
}
