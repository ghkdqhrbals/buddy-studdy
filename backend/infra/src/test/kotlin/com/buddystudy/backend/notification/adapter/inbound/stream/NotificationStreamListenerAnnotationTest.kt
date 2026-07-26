package com.buddystudy.backend.notification.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.notification.adapter.outbound.stream.NotificationRequestedPayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation

class NotificationStreamListenerAnnotationTest {
    @Test
    fun `notification listener uses typed Jackson payload and ACK`() {
        val annotation = NotificationStreamListener::class.declaredFunctions
            .single { it.name == "consumeNotification" }
            .findAnnotation<StreamListener>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.DOMAIN_EVENTS)
        assertThat(annotation.group).isEqualTo("bs-backend-notification")
        assertThat(annotation.eventType).isEqualTo("NOTIFICATION_REQUESTED")
        assertThat(annotation.payloadType).isEqualTo(NotificationRequestedPayload::class)
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK)
    }

    @Test
    fun `notification recovery uses same typed payload and ACK`() {
        val annotation = NotificationStreamListener::class.declaredFunctions
            .single { it.name == "recoverIdleNotification" }
            .findAnnotation<StreamScheduler>()!!

        assertThat(annotation.topic).isEqualTo(RedisStreamTopic.DOMAIN_EVENTS)
        assertThat(annotation.group).isEqualTo("bs-backend-notification")
        assertThat(annotation.eventType).isEqualTo("NOTIFICATION_REQUESTED")
        assertThat(annotation.payloadType).isEqualTo(NotificationRequestedPayload::class)
        assertThat(annotation.options).isEqualTo(StreamOptions.ACK)
        assertThat(annotation.minIdleTimeMs).isEqualTo(300_000)
    }
}
