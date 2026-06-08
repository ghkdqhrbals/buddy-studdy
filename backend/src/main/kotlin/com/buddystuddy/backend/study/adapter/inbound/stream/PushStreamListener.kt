package com.buddystuddy.backend.study.adapter.inbound.stream

import com.buddystuddy.backend.study.adapter.outbound.apns.ApnsPushService
import com.buddystuddy.backend.auth.adapter.outbound.persistence.DeviceRepository
import com.redisstream.consumer.ConsumedRedisStreamMessage
import com.redisstream.consumer.RedisStreamXNackMode
import com.redisstream.consumer.StreamListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class PushStreamListener(
    private val apns: ApnsPushService,
    private val devices: DeviceRepository,
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
            val fields = message.fields + mapOf(
                "apnsToken" to (message.fields["apnsToken"] ?: device?.apnsToken ?: ""),
                "apnsEnvironment" to (message.fields["apnsEnvironment"] ?: device?.apnsEnvironment ?: "production"),
            )
            apns.sendQuestion(fields)
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
}
