package com.buddystuddy.backend.notification.adapter.inbound.stream

import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystuddy.backend.notification.adapter.outbound.stream.NotificationRequestedPayload
import com.buddystuddy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystuddy.backend.notification.application.port.inbound.ProcessNotificationEventUseCase
import com.buddystuddy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
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
    private val userDevices: UserDevicePort,
    private val pushPublisher: QuestionPushPublishPort,
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
        val targetDevices = devices.findAllByUserId(command.userId)
            .filter { it.apnsToken.isNotBlank() }
            .filter { userDevices.hasActiveSession(command.userId, it.deviceId) }
        if (targetDevices.isEmpty()) {
            notifications.markPushFailed(notificationId, "No active APNs target.", Instant.now())
            return
        }
        try {
            val metadata = NotificationPushMetadata.from(command.metadataJson)
            val failedDeviceIds = targetDevices.mapNotNull { device ->
                val published = pushPublisher.publishPush(
                    QuestionPushRequest(
                        recordId = metadata.recordId ?: command.threadId?.toLongOrNull() ?: notificationId,
                        studyId = metadata.studyId,
                        deviceId = device.deviceId,
                        userId = command.userId,
                        question = command.body,
                        expectedAnswerHint = null,
                        topic = metadata.topic ?: command.threadType ?: "notification",
                        difficultyLevel = metadata.difficultyLevel ?: 1,
                        language = metadata.language ?: "ko",
                        sound = metadata.sound ?: "default",
                        intervalMinutes = metadata.intervalMinutes ?: 0,
                        title = command.title,
                        body = command.body,
                        deepLink = command.deepLink ?: "buddystuddy://notifications/$notificationId",
                    )
                )
                if (published) null else device.deviceId
            }
            if (failedDeviceIds.isNotEmpty()) {
                notifications.markPushFailed(notificationId, "Push publish failed for devices: ${failedDeviceIds.joinToString(",")}", Instant.now())
                return
            }
            notifications.markPushSent(notificationId, Instant.now())
        } catch (error: Exception) {
            notifications.markPushFailed(notificationId, error.message ?: error.javaClass.simpleName, Instant.now())
        }
    }
}

private data class NotificationPushMetadata(
    val recordId: Long? = null,
    val studyId: Long? = null,
    val topic: String? = null,
    val difficultyLevel: Int? = null,
    val language: String? = null,
    val sound: String? = null,
    val intervalMinutes: Int? = null,
) {
    companion object {
        private val mapper = jacksonObjectMapper().findAndRegisterModules()

        fun from(raw: String?): NotificationPushMetadata =
            raw?.takeIf(String::isNotBlank)?.let {
                runCatching { mapper.readValue<NotificationPushMetadata>(it) }.getOrNull()
            } ?: NotificationPushMetadata()
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
