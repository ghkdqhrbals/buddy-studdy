package com.buddystuddy.backend.study.adapter.inbound.stream

import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystuddy.backend.study.application.port.outbound.ApnsAlert
import com.buddystuddy.backend.study.application.port.outbound.ApnsAps
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionPayload
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystuddy.backend.study.application.port.outbound.FcmQuestionMessage
import com.buddystuddy.backend.study.application.port.outbound.PushMessageType
import com.buddystuddy.backend.study.application.port.outbound.PushNotificationPort
import com.buddystuddy.backend.study.application.port.outbound.PushQuestionMessage
import com.buddystuddy.backend.study.adapter.outbound.stream.QuestionPushRequestedPayload
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.redisstream.consumer.ConsumedRedisStreamMessage
import com.redisstream.consumer.RedisStreamXNackMode
import com.redisstream.consumer.StreamConfiguration
import com.redisstream.consumer.StreamListener
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory

@StreamConfiguration
class PushStreamListener(
    private val pushNotifications: PushNotificationPort,
    private val devices: DevicePort,
    private val userDevices: UserDevicePort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun logInitialized() {
        logger.info(
            "push_stream_listener_initialized listener={} streamPrefix={} groupId={} concurrency={} autoStartup={}",
            "buddystuddy-push-listener",
            "\${PUSH_STREAM_PREFIX:push-v1}",
            "\${PUSH_CONSUMER_GROUP_NAME:\${PUSH_CONSUMER_GROUP:bs-backend}}",
            "\${PUSH_CONSUMER_MEMBER_CONCURRENCY:\${PUSH_CONSUMER_RUNTIME_MAX_CONCURRENCY:8}}",
            "\${buddystuddy.streams.enabled:true}",
        )
    }

    @StreamListener(
        id = "buddystuddy-push-listener",
        streamPrefix = "\${PUSH_STREAM_PREFIX:push-v1}",
        groupId = "\${PUSH_CONSUMER_GROUP_NAME:\${PUSH_CONSUMER_GROUP:bs-backend}}",
        concurrency = "\${PUSH_CONSUMER_MEMBER_CONCURRENCY:\${PUSH_CONSUMER_RUNTIME_MAX_CONCURRENCY:8}}",
        autoStartup = "\${buddystuddy.streams.enabled:true}",
        pollBatchSize = "\${PUSH_CONSUMER_REDIS_POLL_BATCH_SIZE:50}",
        pollTimeoutMs = "\${PUSH_CONSUMER_REDIS_POLL_TIMEOUT_MS:3000}",
    )
    fun onPushRequested(message: ConsumedRedisStreamMessage) {
        try {
            logger.info(
                "redis_stream_consume_started listener={} stream={} redisRecordId={} eventId={} eventType={} recordId={} deviceId={} userId={} fieldKeys={}",
                "buddystuddy-push-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["recordId"],
                message.fields["deviceId"],
                message.fields["userId"],
                message.fields.keys,
            )
            val deviceId = PushEventPayloadParser.deviceId(message.fields)
            val userId = PushEventPayloadParser.userId(message.fields)
            if (deviceId != null && userId != null && !userDevices.hasActiveSession(userId, deviceId)) {
                logger.info(
                    "redis_stream_consume_skipped_inactive_session listener={} stream={} redisRecordId={} eventId={} recordId={} deviceId={} userId={}",
                    "buddystuddy-push-listener",
                    message.streamKey,
                    message.recordId,
                    message.fields["eventId"],
                    message.fields["recordId"],
                    deviceId,
                    userId,
                )
                message.ack()
                return
            }
            val device = deviceId?.let { devices.findByDeviceId(it) }
            val pushMessage = PushEventPayloadParser.toPushQuestionMessage(
                fields = message.fields,
                apnsToken = message.fields["apnsToken"] ?: device?.apnsToken ?: "",
                apnsEnvironment = message.fields["apnsEnvironment"] ?: device?.apnsEnvironment ?: "production",
            )
            pushNotifications.sendQuestion(pushMessage)
            message.ack()
            logger.info(
                "redis_stream_consume_succeeded listener={} stream={} redisRecordId={} eventId={} eventType={} recordId={} deviceId={} userId={} pushProvider={}",
                "buddystuddy-push-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["recordId"],
                message.fields["deviceId"],
                message.fields["userId"],
                message.fields["pushProvider"] ?: message.fields["provider"] ?: PushMessageType.APNS.name,
            )
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_consume_failed listener={} stream={} redisRecordId={} eventId={} eventType={} recordId={} deviceId={} userId={} error={}",
                "buddystuddy-push-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["recordId"],
                message.fields["deviceId"],
                message.fields["userId"],
                error.message,
            )
            message.nack(RedisStreamXNackMode.SILENT, 30_000, false)
        }
    }

}

internal object PushEventPayloadParser {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    fun deviceId(fields: Map<String, String>): String? =
        payload(fields)?.deviceId ?: fields["deviceId"]?.takeIf(String::isNotBlank)

    fun userId(fields: Map<String, String>): Long? =
        payload(fields)?.userId ?: fields["userId"]?.toLongOrNull()

    fun toPushQuestionMessage(
        fields: Map<String, String>,
        apnsToken: String,
        apnsEnvironment: String,
    ): PushQuestionMessage {
        val payload = payload(fields)
        val provider = fields["pushProvider"] ?: fields["provider"] ?: PushMessageType.APNS.name
        val common = if (payload != null) {
            QuestionPushMessageFields(
                recordId = payload.recordId.toString(),
                studyId = payload.studyId?.toString(),
                question = payload.question,
                title = payload.title,
                body = payload.body,
                topic = payload.topic,
                sound = payload.sound?.takeIf(String::isNotBlank),
                deepLink = payload.deepLink,
            )
        } else {
            QuestionPushMessageFields(
                recordId = fields["recordId"] ?: "",
                studyId = fields["studyId"]?.takeIf(String::isNotBlank),
                question = fields["question"] ?: "A new study question is ready.",
                title = fields["title"],
                body = fields["body"],
                topic = fields["topic"] ?: "",
                sound = fields["sound"]?.takeIf(String::isNotBlank),
                deepLink = fields["deepLink"],
            )
        }
        val deepLink = common.deepLink ?: PushDeepLinkFactory.studyRoomOrRecord(common.studyId, common.recordId)
        return when (provider.uppercase()) {
            PushMessageType.FCM.name -> FcmQuestionMessage(
                recordId = common.recordId,
                question = common.body ?: common.question,
                topic = common.topic,
                sound = common.sound,
                deepLink = deepLink,
                token = fields["fcmToken"] ?: fields["pushToken"] ?: "",
            )
            else -> ApnsQuestionMessage(
                recordId = common.recordId,
                topic = common.topic,
                token = apnsToken,
                environment = apnsEnvironment,
                payload = ApnsQuestionPayload(
                    aps = ApnsAps(
                        alert = ApnsAlert(
                            title = common.title ?: "BuddyStuddy",
                            body = common.body ?: common.question,
                        ),
                        sound = common.sound ?: "default",
                    ),
                    deepLink = deepLink,
                ),
            )
        }
    }

    private fun payload(fields: Map<String, String>): QuestionPushRequestedPayload? =
        fields["payload"]?.takeIf(String::isNotBlank)?.let {
            mapper.readValue<QuestionPushRequestedPayload>(it)
        }

    private data class QuestionPushMessageFields(
        val recordId: String,
        val studyId: String?,
        val question: String,
        val title: String?,
        val body: String?,
        val topic: String,
        val sound: String?,
        val deepLink: String?,
    )
}

internal object PushDeepLinkFactory {
    fun studyRoomOrRecord(studyId: String?, recordId: String): String =
        studyId
            ?.takeIf(String::isNotBlank)
            ?.let { "buddystuddy://studies/$it" }
            ?: "buddystuddy://records/$recordId"
}
