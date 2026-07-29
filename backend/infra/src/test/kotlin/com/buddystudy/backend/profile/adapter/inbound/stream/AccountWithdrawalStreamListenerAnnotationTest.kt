package com.buddystudy.backend.profile.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.profile.application.model.AccountWithdrawnEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation

class AccountWithdrawalStreamListenerAnnotationTest {
    @Test
    fun `withdrawal listener uses a typed at-least-once payload`() {
        val annotation = AccountWithdrawalStreamListener::class.declaredFunctions
            .single { it.name == "consume" }
            .findAnnotation<StreamListener>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.IDENTITY_ACCOUNT_WITHDRAWN)
        assertThat(annotation.legacyTopic).isEqualTo(RedisStreamTopic.LEGACY_DOMAIN_EVENTS)
        assertThat(annotation.group).isEqualTo("bs-backend-account-withdrawal")
        assertThat(annotation.eventType).isEqualTo("ACCOUNT_WITHDRAWN")
        assertThat(annotation.payloadType).isEqualTo(AccountWithdrawnEvent::class)
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK)
    }

    @Test
    fun `withdrawal recovery reclaims idle events`() {
        val annotation = AccountWithdrawalStreamListener::class.declaredFunctions
            .single { it.name == "recover" }
            .findAnnotation<StreamScheduler>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.IDENTITY_ACCOUNT_WITHDRAWN)
        assertThat(annotation.legacyTopic).isEqualTo(RedisStreamTopic.LEGACY_DOMAIN_EVENTS)
        assertThat(annotation.group).isEqualTo("bs-backend-account-withdrawal")
        assertThat(annotation.eventType).isEqualTo("ACCOUNT_WITHDRAWN")
        assertThat(annotation.payloadType).isEqualTo(AccountWithdrawnEvent::class)
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK)
        assertThat(annotation.minIdleTimeMs).isEqualTo(300_000)
    }
}
