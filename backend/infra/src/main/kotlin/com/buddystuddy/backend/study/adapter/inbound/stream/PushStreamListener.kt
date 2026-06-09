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
        streamPrefix = "\${buddystuddy.streams.push-prefix:bs-push-v1}",
        groupId = "bs-push-workers",
        concurrency = "2",
        autoStartup = "\${buddystuddy.streams.enabled:true}",
        pollBatchSize = "50",
        pollTimeoutMs = "3000",
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
            val device = message.fields["deviceId"]?.let { devices.findByDeviceId(it) }
            val pushMessage = message.fields.toPushQuestionMessage(
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

    private fun Map<String, String>.toPushQuestionMessage(
        apnsToken: String,
        apnsEnvironment: String,
    ): PushQuestionMessage {
        val provider = this["pushProvider"] ?: this["provider"] ?: PushMessageType.APNS.name
        val common = QuestionPushMessageFields(
            recordId = this["recordId"] ?: "",
            question = this["question"] ?: "A new study question is ready.",
            topic = this["topic"] ?: "",
            sound = this["sound"]?.takeIf(String::isNotBlank),
        )
        val deepLink = "buddystuddy://records/${common.recordId}"
        return when (provider.uppercase()) {
            PushMessageType.FCM.name -> FcmQuestionMessage(
                recordId = common.recordId,
                question = common.question,
                topic = common.topic,
                sound = common.sound,
                deepLink = deepLink,
                token = this["fcmToken"] ?: this["pushToken"] ?: "",
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

    private data class QuestionPushMessageFields(
        val recordId: String,
        val question: String,
        val topic: String,
        val sound: String?,
    )
}
