package com.buddystudy.backend.notification.adapter.outbound.stream

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.outbound.NotificationStreamPublishPort
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class NotificationRedisStreamPublisher(
    private val properties: BuddyStudyProperties,
    @Qualifier("notificationStreamPublisher") publisherProvider: ObjectProvider<RedisStreamPublisher>,
) : NotificationStreamPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val publisher = publisherProvider.ifAvailable

    init {
        if (properties.streams.enabled) {
            requireNotNull(publisher) { "notificationStreamPublisher bean is required when buddystudy.streams.enabled=true" }
        }
    }

    override fun publishNotification(command: NotificationRequestCommand): Boolean {
        if (!properties.streams.enabled) {
            logger.debug("redis_stream_publish_skipped reason=streams_disabled prefix={} eventId={}", properties.streams.notificationPrefix, command.eventId)
            return false
        }
        val event = NotificationRequestedEvent(
            eventId = command.eventId,
            userId = command.userId,
            actorUserId = command.actorUserId,
            type = command.type,
            title = command.title,
            body = command.body,
            threadType = command.threadType,
            threadId = command.threadId,
            deepLink = command.deepLink,
            metadataJson = command.metadataJson,
            shouldPush = command.shouldPush,
        )
        val fields = event.toRedisStreamFields()
        return try {
            logger.debug(
                "redis_stream_publish_started prefix={} eventId={} eventType={} userId={} fieldKeys={}",
                properties.streams.notificationPrefix,
                fields["eventId"],
                fields["eventType"],
                command.userId,
                fields.keys,
            )
            val published = publisher!!.publish(
                null,
                fields,
                RedisStreamPublishOptions(properties.streams.maxLen, true),
            )
            logger.debug(
                "redis_stream_publish_succeeded stream={} redisRecordId={} eventId={} eventType={} userId={}",
                published.streamKey,
                published.recordId,
                fields["eventId"],
                fields["eventType"],
                command.userId,
            )
            true
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_publish_failed prefix={} eventId={} eventType={} userId={} error={}",
                properties.streams.notificationPrefix,
                fields["eventId"],
                fields["eventType"],
                command.userId,
                error.message,
            )
            false
        }
    }
}
