package com.buddystuddy.backend.study.adapter.outbound.stream

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.utils.toStringMapWithoutNull
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class RedisStreamCoordinatorService(
    private val properties: BuddyStuddyProperties,
    @Qualifier("pushStreamPublisher") pushPublisherProvider: ObjectProvider<RedisStreamPublisher>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val pushPublisher: RedisStreamPublisher? = pushPublisherProvider.ifAvailable

    fun publishPush(event: QuestionPushRequestedEvent): Boolean {
        if (!properties.streams.enabled) return false
        val publisher = pushPublisher ?: return false
        val fields = event.toStringMapWithoutNull()
        return try {
            val published = publisher.publish(
                event.topic.ifBlank { event.recordId.toString() },
                fields,
                RedisStreamPublishOptions(properties.streams.maxLen, true),
            )
            logger.info("redis_stream_published stream={} id={} fields={}", published.streamKey, published.recordId, fields.keys)
            true
        } catch (error: Exception) {
            logger.warn("redis_stream_publish_failed prefix={} error={}", properties.streams.pushPrefix, error.message)
            false
        }
    }

}
