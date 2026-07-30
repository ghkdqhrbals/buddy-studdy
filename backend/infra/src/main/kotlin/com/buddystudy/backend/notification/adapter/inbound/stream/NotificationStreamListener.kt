package com.buddystudy.backend.notification.adapter.inbound.stream

import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.notification.adapter.outbound.stream.NotificationRequestedPayload
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.ProcessNotificationEventUseCase
import com.buddystudy.backend.notification.application.port.inbound.RecoverNotificationCommandUseCase
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystudy.backend.study.application.content.MarkdownContentPolicy
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class NotificationStreamListener(
    private val processor: ProcessNotificationEventUseCase,
    private val notifications: NotificationPersistencePort,
    private val devices: DevicePort,
    private val userDevices: UserDevicePort,
    private val pushPublisher: QuestionPushPublishPort,
    private val notificationRecovery: RecoverNotificationCommandUseCase,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @StreamListener(
        topic = RedisStreamTopic.NOTIFICATION_MESSAGE_REQUESTED,
        group = GROUP,
        consumer = CONSUMER,
        eventType = EVENT_TYPE,
        payloadType = NotificationRequestedPayload::class,
        batchSize = 100,
        blockTimeMs = 3_000,
        pollDelayMs = 1_000,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun consumeNotification(
        payload: NotificationRequestedPayload,
        context: StreamMessageContext,
    ) {
        deliver(payload, context)
    }

    @StreamScheduler(
        topic = RedisStreamTopic.NOTIFICATION_MESSAGE_REQUESTED,
        group = GROUP,
        consumer = RECOVERY_CONSUMER,
        eventType = EVENT_TYPE,
        payloadType = NotificationRequestedPayload::class,
        batchSize = 100,
        minIdleTimeMs = 300_000,
        fixedDelayMs = 30_000,
        initialDelayMs = 30_000,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK,
    )
    private suspend fun recoverIdleNotification(
        payload: NotificationRequestedPayload,
        context: StreamMessageContext,
    ) {
        deliver(payload, context)
    }

    internal suspend fun deliver(
        payload: NotificationRequestedPayload,
        context: StreamMessageContext,
    ) {
        val eventId = payload.eventId?.takeIf(String::isNotBlank)
            ?: context.eventId?.takeIf(String::isNotBlank)
            ?: run {
                discardMalformed(payload, context, "eventId")
                return
            }
        val command = payload.toCommandOrNull(eventId)
            ?: notificationRecovery.recover(eventId)?.also {
                logger.warn(
                    "notification_event_payload_recovered stream={} redisRecordId={} eventId={} eventType={} missingFields={} claimed={}",
                    context.streamKey,
                    context.recordId,
                    eventId,
                    context.eventType,
                    payload.missingRequiredFields(eventIdAvailable = true),
                    context.claimed,
                )
            }
            ?: run {
                discardMalformed(
                    payload,
                    context,
                    payload.missingRequiredFields(eventIdAvailable = true).joinToString(","),
                )
                return
            }
        logger.debug(
            "redis_stream_consume_started listener={} stream={} redisRecordId={} eventId={} eventType={} userId={} claimed={}",
            "buddystudy-notification-listener",
            context.streamKey,
            context.recordId,
            context.eventId,
            context.eventType,
            command.userId,
            context.claimed,
        )
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
        if (command.shouldPush && command.type != DIRECT_PUSH_EVENT_TYPE) {
            publishPush(notificationId, command)
        } else {
            logger.info(
                "notification_push_skipped reason={} notificationId={} eventId={} userId={}",
                if (command.type == DIRECT_PUSH_EVENT_TYPE) "dedicated_push_stream" else "should_push_false",
                notificationId,
                command.eventId,
                command.userId,
            )
        }
    }

    private suspend fun publishPush(notificationId: Long, command: NotificationRequestCommand) {
        val activeSessions = command.userId?.let { userDevices.findActiveByUserId(it) }.orEmpty()
        val candidateDeviceIds = buildList {
            addAll(activeSessions.map { it.deviceId })
            command.deviceId?.let(::add)
        }.distinct()
        val targetDevice = candidateDeviceIds.firstNotNullOfOrNull { deviceId ->
            devices.findByDeviceId(deviceId)?.takeIf { it.apnsToken.isNotBlank() }
        }
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

        try {
            val metadata = NotificationPushMetadata.from(command.metadataJson)
            checkNotNull(
                pushPublisher.publishPush(
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
                        body = MarkdownContentPolicy.plainText(command.body),
                        deepLink = command.deepLink ?: "buddystudy://notifications/$notificationId",
                    ),
                ),
            ) { "Push stream publish failed for device: ${targetDevice.deviceId}" }
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
            throw error
        }
    }

    private fun discardMalformed(
        payload: NotificationRequestedPayload,
        context: StreamMessageContext,
        missingFields: String,
    ) {
        val error = IllegalArgumentException(
            "Notification event payload cannot be recovered; missing fields: ${missingFields.ifBlank { "unknown" }}",
        )
        logger.error(
            "notification_event_discarded reason=malformed_payload stream={} redisRecordId={} eventId={} eventType={} missingFields={} claimed={}",
            context.streamKey,
            context.recordId,
            payload.eventId ?: context.eventId,
            context.eventType,
            missingFields.ifBlank { "unknown" },
            context.claimed,
            error,
        )
    }

    private companion object {
        const val GROUP = "bs-backend-notification"
        const val CONSUMER = "buddystudy-notification"
        const val RECOVERY_CONSUMER = "buddystudy-notification-recovery"
        const val EVENT_TYPE = "NOTIFICATION_REQUESTED"
        const val DIRECT_PUSH_EVENT_TYPE = "STUDY_QUESTION"
    }
}

internal fun NotificationRequestedPayload.toCommandOrNull(eventId: String): NotificationRequestCommand? {
    val resolvedTitle = title ?: return null
    val resolvedBody = body ?: return null
    if (userId == null && deviceId.isNullOrBlank()) return null
    return NotificationRequestCommand(
        eventId = eventId,
        userId = userId,
        deviceId = deviceId,
        actorUserId = actorUserId,
        type = type,
        title = resolvedTitle,
        body = resolvedBody,
        threadType = threadType,
        threadId = threadId,
        deepLink = deepLink,
        metadataJson = metadataJson,
        shouldPush = shouldPush,
    )
}

internal fun NotificationRequestedPayload.missingRequiredFields(
    eventIdAvailable: Boolean = !eventId.isNullOrBlank(),
): List<String> = buildList {
    if (!eventIdAvailable) add("eventId")
    if (userId == null && deviceId.isNullOrBlank()) add("owner")
    if (title == null) add("title")
    if (body == null) add("body")
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
        fun from(raw: String?): NotificationPushMetadata =
            raw?.takeIf(String::isNotBlank)?.let {
                runCatching { JsonMapperProvider.mapper.readValue<NotificationPushMetadata>(it) }.getOrNull()
            } ?: NotificationPushMetadata()
    }
}
