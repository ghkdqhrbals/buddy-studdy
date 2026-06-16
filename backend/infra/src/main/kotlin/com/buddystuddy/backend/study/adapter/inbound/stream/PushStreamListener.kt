package com.buddystuddy.backend.study.adapter.inbound.stream

import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
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
import org.slf4j.LoggerFactory

@StreamConfiguration
class PushStreamListener(
    private val pushNotifications: PushNotificationPort,
    private val devices: DevicePort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @StreamListener(
        id = "buddystuddy-push-listener",
        streamPrefix = "\${PUSH_STREAM_PREFIX:push-v1}",
        groupId = "\${PUSH_CONSUMER_GROUP_NAME:\${PUSH_CONSUMER_GROUP:bs-backend}}",
        concurrency = "\${PUSH_CONSUMER_MEMBER_CONCURRENCY:\${PUSH_CONSUMER_RUNTIME_MAX_CONCURRENCY:4}}",
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
            val device = PushEventPayloadParser.deviceId(message.fields)?.let { devices.findByDeviceId(it) }
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
                topic = payload.topic,
                sound = payload.sound?.takeIf(String::isNotBlank),
            )
        } else {
            QuestionPushMessageFields(
                recordId = fields["recordId"] ?: "",
                studyId = fields["studyId"]?.takeIf(String::isNotBlank),
                question = fields["question"] ?: "A new study question is ready.",
                topic = fields["topic"] ?: "",
                sound = fields["sound"]?.takeIf(String::isNotBlank),
            )
        }
        val deepLink = PushDeepLinkFactory.studyRoomOrRecord(common.studyId, common.recordId)
        return when (provider.uppercase()) {
            PushMessageType.FCM.name -> FcmQuestionMessage(
                recordId = common.recordId,
                question = common.question,
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
                            title = "BuddyStuddy",
                            body = common.question,
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
        val topic: String,
        val sound: String?,
    )
}

internal object PushDeepLinkFactory {
    fun studyRoomOrRecord(studyId: String?, recordId: String): String =
        studyId
            ?.takeIf(String::isNotBlank)
            ?.let { "buddystuddy://studies/$it" }
            ?: "buddystuddy://records/$recordId"
}
