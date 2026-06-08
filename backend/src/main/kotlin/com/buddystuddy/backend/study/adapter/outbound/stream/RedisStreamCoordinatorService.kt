package com.buddystuddy.backend.study.adapter.outbound.stream

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
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
) : QuestionPushPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val pushPublisher: RedisStreamPublisher? = pushPublisherProvider.ifAvailable

    override fun publishPush(request: QuestionPushRequest): Boolean {
        if (!properties.streams.enabled) return false
        val publisher = pushPublisher ?: return false
        val event = QuestionPushRequestedEvent(
            recordId = request.recordId,
            deviceId = request.deviceId,
            userId = request.userId,
            question = request.question,
            expectedAnswerHint = request.expectedAnswerHint,
            topic = request.topic,
            difficultyLevel = request.difficultyLevel,
            language = request.language,
            sound = request.sound,
            intervalMinutes = request.intervalMinutes,
            createdAt = request.createdAt,
        )
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
