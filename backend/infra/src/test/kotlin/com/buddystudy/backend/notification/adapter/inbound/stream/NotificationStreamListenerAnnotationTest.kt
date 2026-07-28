package com.buddystudy.backend.notification.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.notification.adapter.outbound.stream.NotificationRequestedPayload
import com.fasterxml.jackson.module.kotlin.readValue
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

    @Test
    fun `legacy notification payload falls back to Redis envelope event id`() {
        val payload = JsonMapperProvider.mapper.readValue<NotificationRequestedPayload>(
            """
            {
              "type": "ACTIVITY",
              "title": "New answer",
              "body": "A user answered.",
              "threadType": "QUESTION",
              "threadId": "45",
              "deepLink": "buddystudy://questions/45",
              "metadataJson": null,
              "shouldPush": false
            }
            """.trimIndent(),
        )
        val context = StreamMessageContext(
            streamKey = "buddystudy-events-v1",
            recordId = "1785259240567-0",
            eventId = "question-created-45",
            eventType = "NOTIFICATION_REQUESTED",
            fields = emptyMap(),
            claimed = true,
        )

        assertThat(payload.toCommand(context).eventId).isEqualTo("question-created-45")
    }
}
