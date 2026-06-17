package com.buddystuddy.backend.notification.adapter.inbound.stream

import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.notification.adapter.outbound.stream.NotificationRequestedPayload
import com.buddystuddy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystuddy.backend.notification.application.port.inbound.ProcessNotificationEventUseCase
import com.buddystuddy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystuddy.backend.study.application.port.outbound.ApnsAlert
import com.buddystuddy.backend.study.application.port.outbound.ApnsAps
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionPayload
import com.buddystuddy.backend.study.application.port.outbound.PushNotificationPort
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.redisstream.consumer.ConsumedRedisStreamMessage
import com.redisstream.consumer.RedisStreamXNackMode
import com.redisstream.consumer.StreamConfiguration
import com.redisstream.consumer.StreamListener
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

@StreamConfiguration
class NotificationStreamListener(
    private val processor: ProcessNotificationEventUseCase,
    private val notifications: NotificationPersistencePort,
    private val devices: DevicePort,
    private val pushNotifications: PushNotificationPort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val stalePushClaimAge = Duration.ofMinutes(5)

    @StreamListener(
        id = "buddystuddy-notification-listener",
        streamPrefix = "\${NOTIFICATION_STREAM_PREFIX:notification-v1}",
        groupId = "\${NOTIFICATION_CONSUMER_GROUP_NAME:\${NOTIFICATION_CONSUMER_GROUP:bs-backend}}",
        concurrency = "\${NOTIFICATION_CONSUMER_MEMBER_CONCURRENCY:\${NOTIFICATION_CONSUMER_RUNTIME_MAX_CONCURRENCY:4}}",
        autoStartup = "\${buddystuddy.streams.enabled:true}",
        pollBatchSize = "\${NOTIFICATION_CONSUMER_REDIS_POLL_BATCH_SIZE:100}",
        pollTimeoutMs = "\${NOTIFICATION_CONSUMER_REDIS_POLL_TIMEOUT_MS:3000}",
    )
    fun onNotificationRequested(message: ConsumedRedisStreamMessage) {
        try {
            logger.debug(
                "redis_stream_consume_started listener={} stream={} redisRecordId={} eventId={} eventType={} userId={} fieldKeys={}",
                "buddystuddy-notification-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["userId"],
                message.fields.keys,
            )
            val command = NotificationPayloadParser.toCommand(message.fields)
            val notificationId = processor.process(command)
            if (command.shouldPush) {
                sendPushIfClaimed(notificationId, command)
            }
            message.ack()
            logger.debug(
                "redis_stream_consume_succeeded listener={} stream={} redisRecordId={} eventId={} eventType={} userId={} notificationId={}",
                "buddystuddy-notification-listener",
                message.streamKey,
                message.recordId,
                command.eventId,
                "NOTIFICATION_REQUESTED",
                command.userId,
                notificationId,
            )
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_consume_failed listener={} stream={} redisRecordId={} eventId={} eventType={} userId={} error={}",
                "buddystuddy-notification-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["userId"],
                error.message,
            )
            message.nack(RedisStreamXNackMode.SILENT, 30_000, false)
        }
    }

    private fun sendPushIfClaimed(notificationId: Long, command: NotificationRequestCommand) {
        val now = Instant.now()
        if (notifications.claimPush(notificationId, now, now.minus(stalePushClaimAge)) == 0) {
            return
        }
        val targetDevices = devices.findAllByUserId(command.userId).filter { it.apnsToken.isNotBlank() }
        if (targetDevices.isEmpty()) {
            notifications.markPushFailed(notificationId, "No APNs token.", Instant.now())
            return
        }
        try {
            val unreadCount = notifications
                .countByUserIdAndReadAtIsNullAndDeletedAtIsNull(command.userId)
                .toInt()
                .coerceAtLeast(1)
            targetDevices.forEach { device ->
                pushNotifications.sendQuestion(
                    ApnsQuestionMessage(
                        recordId = notificationId.toString(),
                        topic = command.threadType ?: "notification",
                        token = device.apnsToken,
                        environment = device.apnsEnvironment,
                        payload = ApnsQuestionPayload(
                            aps = ApnsAps(
                                alert = ApnsAlert(title = command.title, body = command.body),
                                sound = "default",
                                badge = unreadCount,
                            ),
                            deepLink = command.deepLink ?: "buddystuddy://notifications/$notificationId",
                        ),
                    )
                )
            }
            notifications.markPushSent(notificationId, Instant.now())
        } catch (error: Exception) {
            notifications.markPushFailed(notificationId, error.message ?: error.javaClass.simpleName, Instant.now())
        }
    }
}

internal object NotificationPayloadParser {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    fun toCommand(fields: Map<String, String>): NotificationRequestCommand {
        fields["payload"]?.takeIf(String::isNotBlank)?.let {
            val payload = mapper.readValue<NotificationRequestedPayload>(it)
            return NotificationRequestCommand(
                eventId = payload.eventId,
                userId = payload.userId,
                actorUserId = payload.actorUserId,
                type = payload.type,
                title = payload.title,
                body = payload.body,
                threadType = payload.threadType,
                threadId = payload.threadId,
                deepLink = payload.deepLink,
                metadataJson = payload.metadataJson,
                shouldPush = payload.shouldPush,
            )
        }
        return NotificationRequestCommand(
            eventId = fields["eventId"] ?: throw IllegalArgumentException("eventId is required."),
            userId = fields["userId"]?.toLongOrNull() ?: throw IllegalArgumentException("userId is required."),
            actorUserId = fields["actorUserId"]?.toLongOrNull(),
            type = fields["type"] ?: "ACTIVITY",
            title = fields["title"] ?: "BuddyStuddy",
            body = fields["body"] ?: "",
            threadType = fields["threadType"],
            threadId = fields["threadId"],
            deepLink = fields["deepLink"],
            metadataJson = fields["metadataJson"],
            shouldPush = fields["shouldPush"].toBoolean(),
        )
    }
}
