package com.buddystuddy.backend.study.adapter.inbound.stream

import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.study.application.port.outbound.ApnsAlert
import com.buddystuddy.backend.study.application.port.outbound.ApnsAps
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionPayload
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystuddy.backend.study.application.port.outbound.FcmQuestionMessage
import com.buddystuddy.backend.study.application.port.outbound.PushLanding
import com.buddystuddy.backend.study.application.port.outbound.PushLandingPage
import com.buddystuddy.backend.study.application.port.outbound.PushMessageType
import com.buddystuddy.backend.study.application.port.outbound.PushNotificationPort
import com.buddystuddy.backend.study.application.port.outbound.PushQuestionMessage
import com.redisstream.consumer.ConsumedRedisStreamMessage
import com.redisstream.consumer.RedisStreamXNackMode
import com.redisstream.consumer.StreamListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
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
            val device = message.fields["deviceId"]?.let { devices.findByDeviceId(it) }
            val pushMessage = message.fields.toPushQuestionMessage(
                apnsToken = message.fields["apnsToken"] ?: device?.apnsToken ?: "",
                apnsEnvironment = message.fields["apnsEnvironment"] ?: device?.apnsEnvironment ?: "production",
            )
            pushNotifications.sendQuestion(pushMessage)
            message.ack()
        } catch (error: Exception) {
            logger.warn(
                "push_stream_consume_failed stream={} recordId={} error={}",
                message.streamKey,
                message.recordId,
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
        val landing = PushLanding(
            page = PushLandingPage.STUDY_RECORD,
            recordId = common.recordId,
            route = "/study/records/${common.recordId}",
            topic = common.topic,
        )
        return when (provider.uppercase()) {
            PushMessageType.FCM.name -> FcmQuestionMessage(
                recordId = common.recordId,
                question = common.question,
                topic = common.topic,
                sound = common.sound,
                landing = landing,
                token = this["fcmToken"] ?: this["pushToken"] ?: "",
            )
            else -> ApnsQuestionMessage(
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
                    recordId = common.recordId,
                    topic = common.topic,
                    landing = landing,
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
