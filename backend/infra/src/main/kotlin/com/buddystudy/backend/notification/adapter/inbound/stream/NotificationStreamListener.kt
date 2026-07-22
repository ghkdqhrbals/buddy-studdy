package com.buddystudy.backend.notification.adapter.inbound.stream

import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamConsumer
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.ApplicationCoroutineScope
import com.buddystudy.backend.notification.adapter.outbound.stream.NotificationRequestedPayload
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.ProcessNotificationEventUseCase
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.backend.notification.application.service.NotificationSendPolicy
import com.buddystudy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class NotificationStreamListener(
    private val properties: BuddyStudyProperties,
    private val consumer: RedisStreamConsumer,
    private val processor: ProcessNotificationEventUseCase,
    private val notifications: NotificationPersistencePort,
    private val devices: DevicePort,
    private val userDevices: UserDevicePort,
    private val pushPublisher: QuestionPushPublishPort,
    private val sendPolicy: NotificationSendPolicy,
    private val coroutineScope: ApplicationCoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val stalePushClaimAge = Duration.ofMinutes(5)
    private val group = "bs-backend-notification"
    private val consumerName = "notification-${java.net.InetAddress.getLocalHost().hostName}"
    private val eventType = "NOTIFICATION_REQUESTED"

    @Scheduled(fixedDelayString = "\${NOTIFICATION_CONSUMER_POLL_DELAY_MS:1000}")
    fun pollNotificationRequests() {
        consumer.poll(properties.streams.key, group, consumerName, 100, Duration.ofMillis(3000)) {
            coroutineScope.launch { onNotificationRequested(it) }
        }
    }

    suspend fun onNotificationRequested(message: RedisStreamMessage) {
        try {
            if (message.fields["eventType"] != eventType) {
                consumer.acknowledge(message, group)
                return
            }
            logger.debug(
                "redis_stream_consume_started listener={} stream={} redisRecordId={} eventId={} eventType={} userId={} fieldKeys={}",
                "buddystudy-notification-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["userId"],
                message.fields.keys,
            )
            val command = NotificationPayloadParser.toCommand(message.fields)
            val notificationId = processor.process(command)
            logger.info(
                "notification_event_processed notificationId={} eventId={} userId={} shouldPush={} threadType={} threadId={} deepLink={}",
                notificationId,
                command.eventId,
                command.userId,
                command.shouldPush,
                command.threadType,
                command.threadId,
                command.deepLink,
            )
            if (command.shouldPush) {
                sendPushIfClaimed(notificationId, command)
            } else {
                logger.info(
                    "notification_push_skipped reason=should_push_false notificationId={} eventId={} userId={}",
                    notificationId,
                    command.eventId,
                    command.userId,
                )
            }
            consumer.acknowledge(message, group)
            logger.debug(
                "redis_stream_consume_succeeded listener={} stream={} redisRecordId={} eventId={} eventType={} userId={} notificationId={}",
                "buddystudy-notification-listener",
                message.streamKey,
                message.recordId,
                command.eventId,
                eventType,
                command.userId,
                notificationId,
            )
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_consume_failed listener={} stream={} redisRecordId={} eventId={} eventType={} userId={} error={}",
                "buddystudy-notification-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["userId"],
                error.message,
            )
        }
    }

    private suspend fun sendPushIfClaimed(notificationId: Long, command: NotificationRequestCommand) {
        val now = Instant.now()
        if (notifications.claimPush(notificationId, now, now.minus(stalePushClaimAge)) == 0) {
            logger.info(
                "notification_push_skipped reason=claim_not_acquired notificationId={} eventId={} userId={}",
                notificationId,
                command.eventId,
                command.userId,
            )
            return
        }
        val currentSession = command.userId?.let { userDevices.findActiveByUserId(it).firstOrNull() }
        val targetDeviceId = currentSession?.deviceId ?: command.deviceId
        val targetDevice = targetDeviceId
            ?.let { devices.findByDeviceId(it) }
            ?.takeIf { it.apnsToken.isNotBlank() }
        logger.info(
            "notification_push_targets_resolved notificationId={} eventId={} userId={} currentDeviceId={} hasApnsToken={}",
            notificationId,
            command.eventId,
            command.userId,
            currentSession?.deviceId,
            targetDevice != null,
        )
        if (targetDevice == null) {
            notifications.markPushFailed(notificationId, "No active APNs target.", Instant.now())
            logger.info(
                "notification_push_failed reason=no_active_apns_target notificationId={} eventId={} userId={}",
                notificationId,
                command.eventId,
                command.userId,
            )
            return
        }
        val policyCommand = command.copy(
            userId = command.userId ?: targetDevice.userId,
            deviceId = targetDevice.deviceId,
        )
        if (!sendPolicy.canSendPush(policyCommand)) {
            notifications.markPushFailed(notificationId, "Push policy denied.", Instant.now())
            logger.info(
                "notification_push_skipped reason=send_policy_denied notificationId={} eventId={} userId={} deviceId={} type={}",
                notificationId,
                command.eventId,
                policyCommand.userId,
                targetDevice.deviceId,
                command.type,
            )
            return
        }
        try {
            val metadata = NotificationPushMetadata.from(command.metadataJson)
            val published = pushPublisher.publishPush(
                QuestionPushRequest(
                    recordId = metadata.recordId ?: command.threadId?.toLongOrNull() ?: notificationId,
                    notificationId = notificationId,
                    studyId = metadata.studyId,
                    deviceId = targetDevice.deviceId,
                    userId = command.userId ?: targetDevice.userId ?: 0,
                    question = command.body,
                    expectedAnswerHint = null,
                    topic = metadata.topic ?: command.threadType ?: "notification",
                    difficultyLevel = metadata.difficultyLevel ?: 1,
                    language = metadata.language ?: "ko",
                    sound = metadata.sound ?: "default",
                    intervalMinutes = metadata.intervalMinutes ?: 0,
                    title = command.title,
                    body = command.body,
                    deepLink = command.deepLink ?: "buddystudy://notifications/$notificationId",
                )
            )
            if (!published) {
                notifications.markPushFailed(notificationId, "Push publish failed for device: ${targetDevice.deviceId}", Instant.now())
                logger.warn(
                    "notification_push_failed reason=push_stream_publish_failed notificationId={} eventId={} userId={} deviceId={}",
                    notificationId,
                    command.eventId,
                    command.userId,
                    targetDevice.deviceId,
                )
                return
            }
            notifications.markPushSent(notificationId, Instant.now())
            logger.info(
                "notification_push_published notificationId={} eventId={} userId={} deviceId={}",
                notificationId,
                command.eventId,
                command.userId,
                targetDevice.deviceId,
            )
        } catch (error: Exception) {
            notifications.markPushFailed(notificationId, error.message ?: error.javaClass.simpleName, Instant.now())
            logger.warn(
                "notification_push_failed reason=exception notificationId={} eventId={} userId={} error={}",
                notificationId,
                command.eventId,
                command.userId,
                error.message,
            )
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
                deviceId = payload.deviceId,
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
            userId = fields["userId"]?.toLongOrNull(),
            deviceId = fields["deviceId"],
            actorUserId = fields["actorUserId"]?.toLongOrNull(),
            type = fields["type"] ?: "ACTIVITY",
            title = fields["title"] ?: "BuddyStudy",
            body = fields["body"] ?: "",
            threadType = fields["threadType"],
            threadId = fields["threadId"],
            deepLink = fields["deepLink"],
            metadataJson = fields["metadataJson"],
            shouldPush = fields["shouldPush"].toBoolean(),
        )
    }
}
