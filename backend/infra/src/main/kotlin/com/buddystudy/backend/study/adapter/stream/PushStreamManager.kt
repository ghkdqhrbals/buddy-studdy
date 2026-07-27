package com.buddystudy.backend.study.adapter.stream

import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.common.adapter.stream.RedisStreamObjectPublisher
import com.buddystudy.backend.common.adapter.stream.StreamListener
import com.buddystudy.backend.common.adapter.stream.StreamMessageContext
import com.buddystudy.backend.common.adapter.stream.StreamOptions
import com.buddystudy.backend.common.adapter.stream.StreamScheduler
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.ProcessNotificationEventUseCase
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.backend.notification.application.service.NotificationSendPolicy
import com.buddystudy.backend.study.adapter.outbound.stream.QuestionPushRequestedEvent
import com.buddystudy.backend.study.adapter.outbound.stream.QuestionPushRequestedPayload
import com.buddystudy.backend.study.adapter.outbound.stream.toPayload
import com.buddystudy.backend.study.application.port.outbound.ApnsAlert
import com.buddystudy.backend.study.application.port.outbound.ApnsAps
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionPayload
import com.buddystudy.backend.study.application.port.outbound.FcmQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.PushMessageType
import com.buddystudy.backend.study.application.port.outbound.PushNotificationPort
import com.buddystudy.backend.study.application.port.outbound.PushQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class PushStreamManager(
    private val properties: BuddyStudyProperties,
    private val publisher: RedisStreamObjectPublisher,
    private val pushNotifications: PushNotificationPort,
    private val devices: DevicePort,
    private val userDevices: UserDevicePort,
    private val notifications: NotificationPersistencePort,
    private val notificationProcessor: ProcessNotificationEventUseCase,
    private val notificationSendPolicy: NotificationSendPolicy,
) : QuestionPushPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val stalePushClaimAge = Duration.ofMinutes(5)

    override suspend fun publishPush(request: QuestionPushRequest): Boolean {
        if (!properties.streams.enabled) {
            logger.info(
                "redis_stream_publish_skipped reason=streams_disabled eventType={} recordId={} deviceId={} userId={}",
                EVENT_TYPE,
                request.recordId,
                request.deviceId,
                request.userId,
            )
            return false
        }
        val prepared = prepareForPublish(request) ?: return false
        val event = prepared.request.toEvent()
        val publishStartedAt = Instant.now()
        logger.info(
            "redis_stream_publish_started streamKey={} eventId={} eventType={} recordId={} deviceId={} userId={} topic={} pushCreatedAt={} publishAgeMs={}",
            properties.streams.pushKey,
            event.eventId,
            EVENT_TYPE,
            event.recordId,
            event.deviceId,
            event.userId,
            event.topic,
            event.createdAt,
            Duration.between(event.createdAt, publishStartedAt).toMillis(),
        )
        return try {
            val published = publisher.publish(
                topic = RedisStreamTopic.PUSH_EVENTS,
                eventType = EVENT_TYPE,
                eventId = event.eventId,
                payload = event.toPayload(),
                fields = mapOf(
                    "pushProvider" to PushMessageType.APNS.name,
                    "apnsToken" to prepared.apnsToken,
                    "apnsEnvironment" to prepared.apnsEnvironment,
                ),
            )
            val publishedAt = Instant.now()
            logger.info(
                "redis_stream_publish_succeeded stream={} redisRecordId={} eventId={} eventType={} recordId={} deviceId={} userId={} pushCreatedAt={} publishAgeMs={}",
                published.streamKey,
                published.recordId,
                event.eventId,
                EVENT_TYPE,
                event.recordId,
                event.deviceId,
                event.userId,
                event.createdAt,
                Duration.between(event.createdAt, publishedAt).toMillis(),
            )
            true
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_publish_failed streamKey={} eventId={} eventType={} recordId={} deviceId={} userId={} error={}",
                properties.streams.pushKey,
                event.eventId,
                EVENT_TYPE,
                event.recordId,
                event.deviceId,
                event.userId,
                error.message,
            )
            false
        }
    }

    private suspend fun prepareForPublish(request: QuestionPushRequest): PreparedPush? {
        val notificationCommand = request.toQuestionNotificationCommand()
        val notificationId = request.notificationId ?: notificationProcessor.process(notificationCommand)

        suspend fun reject(reason: String, detail: String): PreparedPush? {
            notifications.markPushFailed(notificationId, detail, Instant.now())
            logger.info(
                "redis_stream_publish_rejected reason={} eventId={} recordId={} notificationId={} deviceId={} userId={}",
                reason,
                request.eventId,
                request.recordId,
                notificationId,
                request.deviceId,
                request.userId,
            )
            return null
        }

        if (!notificationSendPolicy.canSendPush(notificationCommand)) {
            return reject("send_policy_denied", "Push policy denied.")
        }
        val userId = request.userId
        if (userId != null && !userDevices.hasActiveSession(userId, request.deviceId)) {
            return reject("inactive_session", "Push target session is inactive.")
        }
        val device = devices.findByDeviceId(request.deviceId)
            ?: return reject("device_not_found", "Push target device was not found.")
        val apnsToken = device.apnsToken.trim()
        if (apnsToken.isBlank()) {
            return reject("apns_token_missing", "APNs token is missing.")
        }
        val missingCredentials = missingApnsCredentials()
        if (missingCredentials.isNotEmpty()) {
            logger.warn(
                "redis_stream_publish_rejected reason=apns_credentials_missing missing={} eventId={} recordId={} notificationId={} deviceId={} userId={}",
                missingCredentials.joinToString(","),
                request.eventId,
                request.recordId,
                notificationId,
                request.deviceId,
                request.userId,
            )
            notifications.markPushFailed(notificationId, "APNs credentials are not configured.", Instant.now())
            return null
        }
        val claimTime = Instant.now()
        if (notifications.claimPush(notificationId, claimTime, claimTime.minus(stalePushClaimAge)) == 0) {
            logger.info(
                "redis_stream_publish_rejected reason=push_claim_not_acquired eventId={} recordId={} notificationId={} deviceId={} userId={}",
                request.eventId,
                request.recordId,
                notificationId,
                request.deviceId,
                request.userId,
            )
            return null
        }
        return PreparedPush(
            request = request.copy(notificationId = notificationId),
            apnsToken = apnsToken,
            apnsEnvironment = device.apnsEnvironment.ifBlank { "production" },
        )
    }

    private fun missingApnsCredentials(): List<String> = buildList {
        if (properties.apns.teamId.isBlank()) add("teamId")
        if (properties.apns.keyId.isBlank()) add("keyId")
        if (properties.apns.authKeyP8.isBlank()) add("authKeyP8")
        if (properties.apns.bundleId.isBlank()) add("bundleId")
    }

    @StreamListener(
        topic = RedisStreamTopic.PUSH_EVENTS,
        group = GROUP,
        consumer = CONSUMER,
        eventType = EVENT_TYPE,
        payloadType = QuestionPushRequestedPayload::class,
        batchSize = 50,
        blockTimeMs = 3_000,
        pollDelayMs = 1_000,
        concurrency = 10,
        concurrencyProperty = "buddystudy.streams.push-consumer-concurrency",
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK_DEL,
    )
    private suspend fun consumePush(
        payload: QuestionPushRequestedPayload,
        context: StreamMessageContext,
    ) {
        deliver(payload, context)
    }

    @StreamScheduler(
        topic = RedisStreamTopic.PUSH_EVENTS,
        group = GROUP,
        consumer = RECOVERY_CONSUMER,
        eventType = EVENT_TYPE,
        payloadType = QuestionPushRequestedPayload::class,
        batchSize = 50,
        minIdleTimeMs = 300_000,
        fixedDelayMs = 30_000,
        initialDelayMs = 30_000,
        enabledProperty = "buddystudy.streams.enabled",
        options = StreamOptions.ACK_DEL,
    )
    private suspend fun recoverIdlePush(
        payload: QuestionPushRequestedPayload,
        context: StreamMessageContext,
    ) {
        deliver(payload, context)
    }

    internal suspend fun deliver(payload: QuestionPushRequestedPayload, context: StreamMessageContext) {
        try {
            logger.info(
                "redis_stream_consume_started stream={} redisRecordId={} eventId={} eventType={} recordId={} notificationId={} deviceId={} userId={} claimed={}",
                context.streamKey,
                context.recordId,
                context.eventId,
                context.eventType,
                payload.recordId,
                payload.notificationId,
                payload.deviceId,
                payload.userId,
                context.claimed,
            )
            val pushMessage = PushEventPayloadMapper.toPushQuestionMessage(
                payload = payload,
                fields = context.fields,
                apnsToken = context.fields["apnsToken"].orEmpty(),
                apnsEnvironment = context.fields["apnsEnvironment"] ?: "production",
            )
            pushNotifications.sendQuestion(pushMessage)
            val consumedAt = Instant.now()
            payload.notificationId?.let { notifications.markPushSent(it, consumedAt) }
            logger.info(
                "redis_stream_consume_succeeded stream={} redisRecordId={} eventId={} eventType={} recordId={} deviceId={} userId={} pushProvider={} pushCreatedAt={} pushAgeMs={} claimed={}",
                context.streamKey,
                context.recordId,
                context.eventId,
                context.eventType,
                payload.recordId,
                payload.deviceId,
                payload.userId,
                pushMessage.type,
                payload.createdAt,
                Duration.between(payload.createdAt, consumedAt).toMillis(),
                context.claimed,
            )
        } catch (error: Exception) {
            payload.notificationId?.let {
                runCatching {
                    notifications.markPushFailed(it, error.message ?: error.javaClass.simpleName, Instant.now())
                }
            }
            throw error
        }
    }

    private fun QuestionPushRequest.toQuestionNotificationCommand(): NotificationRequestCommand =
        NotificationRequestCommand(
            eventId = "question-created-$recordId",
            userId = userId,
            deviceId = deviceId,
            type = "STUDY_QUESTION",
            title = title ?: "BuddyStudy",
            body = body ?: question,
            threadType = "study_question",
            threadId = recordId.toString(),
            deepLink = deepLink ?: PushDeepLinkFactory.studyRoomOrRecord(recordId.toString()),
            metadataJson = JsonMapperProvider.mapper.writeValueAsString(
                mapOf(
                    "recordId" to recordId,
                    "studyId" to studyId,
                    "topic" to topic,
                    "difficultyLevel" to difficultyLevel,
                    "language" to language,
                    "sound" to sound,
                    "intervalMinutes" to intervalMinutes,
                )
            ),
            shouldPush = true,
        )

    private fun QuestionPushRequest.toEvent(): QuestionPushRequestedEvent =
        QuestionPushRequestedEvent(
            eventId = eventId,
            recordId = recordId,
            notificationId = notificationId,
            studyId = studyId,
            deviceId = deviceId,
            userId = userId,
            question = question,
            expectedAnswerHint = expectedAnswerHint,
            topic = topic,
            difficultyLevel = difficultyLevel,
            language = language,
            sound = sound,
            intervalMinutes = intervalMinutes,
            title = title,
            body = body,
            deepLink = deepLink,
            createdAt = createdAt,
        )

    private companion object {
        const val GROUP = "bs-backend-push"
        const val CONSUMER = "buddystudy-push"
        const val RECOVERY_CONSUMER = "buddystudy-push-recovery"
        const val EVENT_TYPE = "QUESTION_PUSH_REQUESTED"
    }

    private data class PreparedPush(
        val request: QuestionPushRequest,
        val apnsToken: String,
        val apnsEnvironment: String,
    )
}

internal object PushEventPayloadMapper {
    fun toPushQuestionMessage(
        payload: QuestionPushRequestedPayload,
        fields: Map<String, String>,
        apnsToken: String,
        apnsEnvironment: String,
    ): PushQuestionMessage {
        val provider = fields["pushProvider"] ?: fields["provider"] ?: PushMessageType.APNS.name
        val recordId = payload.recordId.toString()
        val notificationId = payload.notificationId?.toString()
        val deepLink = payload.deepLink ?: PushDeepLinkFactory.studyRoomOrRecord(recordId)
        return when (provider.uppercase()) {
            PushMessageType.FCM.name -> FcmQuestionMessage(
                recordId = recordId,
                notificationId = notificationId,
                question = payload.body ?: payload.question,
                topic = payload.topic,
                sound = payload.sound?.takeIf(String::isNotBlank),
                deepLink = deepLink,
                token = fields["fcmToken"] ?: fields["pushToken"] ?: "",
                createdAt = payload.createdAt,
            )
            else -> ApnsQuestionMessage(
                recordId = recordId,
                notificationId = notificationId,
                topic = payload.topic,
                token = apnsToken,
                environment = apnsEnvironment,
                payload = ApnsQuestionPayload(
                    aps = ApnsAps(
                        alert = ApnsAlert(
                            title = payload.title ?: "BuddyStudy",
                            body = payload.body ?: payload.question,
                        ),
                        sound = payload.sound?.takeIf(String::isNotBlank) ?: "default",
                    ),
                    deepLink = deepLink,
                    notificationId = notificationId,
                ),
                createdAt = payload.createdAt,
            )
        }
    }
}

internal object PushDeepLinkFactory {
    fun studyRoomOrRecord(recordId: String): String = "buddystudy://records/$recordId"
}
