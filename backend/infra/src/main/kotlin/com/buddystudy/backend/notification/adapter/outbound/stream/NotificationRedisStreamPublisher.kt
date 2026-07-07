package com.buddystudy.backend.notification.adapter.outbound.stream

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.outbound.NotificationStreamPublishPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class NotificationRedisStreamPublisher(
    private val properties: BuddyStudyProperties,
    private val publisher: RedisStreamPublishOperations,
) : NotificationStreamPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun publishNotification(command: NotificationRequestCommand): Boolean {
        if (!properties.streams.enabled) {
            logger.debug("redis_stream_publish_skipped reason=streams_disabled streamKey={} eventId={}", properties.streams.key, command.eventId)
            return false
        }
        val event = NotificationRequestedEvent(
            eventId = command.eventId,
            userId = command.userId,
            deviceId = command.deviceId,
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
                "redis_stream_publish_started streamKey={} eventId={} eventType={} userId={} fieldKeys={}",
                properties.streams.key,
                fields["eventId"],
                fields["eventType"],
                command.userId,
                fields.keys,
            )
            val published = publisher.publish(properties.streams.key, fields)
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
                "redis_stream_publish_failed streamKey={} eventId={} eventType={} userId={} error={}",
                properties.streams.key,
                fields["eventId"],
                fields["eventType"],
                command.userId,
                error.message,
            )
            false
        }
    }
}
