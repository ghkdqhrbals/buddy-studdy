package com.buddystuddy.backend.study.adapter.outbound.stream

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class RedisStreamPushPublisher(
    private val properties: BuddyStuddyProperties,
    @Qualifier("pushStreamPublisher") pushPublisherProvider: ObjectProvider<RedisStreamPublisher>,
) : QuestionPushPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val pushPublisher: RedisStreamPublisher? = pushPublisherProvider.ifAvailable

    init {
        if (properties.streams.enabled) {
            requireNotNull(pushPublisher) { "pushStreamPublisher bean is required when buddystuddy.streams.enabled=true" }
        }
    }

    override fun publishPush(request: QuestionPushRequest): Boolean {
        if (!properties.streams.enabled) {
            logger.info(
                "redis_stream_publish_skipped reason=streams_disabled eventType={} recordId={} deviceId={} userId={}",
                "QUESTION_PUSH_REQUESTED",
                request.recordId,
                request.deviceId,
                request.userId,
            )
            return false
        }
        val publisher = pushPublisher ?: run {
            error("pushStreamPublisher bean is required when buddystuddy.streams.enabled=true")
        }
        val event = QuestionPushRequestedEvent(
            recordId = request.recordId,
            notificationId = request.notificationId,
            studyId = request.studyId,
            deviceId = request.deviceId,
            userId = request.userId,
            question = request.question,
            expectedAnswerHint = request.expectedAnswerHint,
            topic = request.topic,
            difficultyLevel = request.difficultyLevel,
            language = request.language,
            sound = request.sound,
            intervalMinutes = request.intervalMinutes,
            title = request.title,
            body = request.body,
            deepLink = request.deepLink,
            createdAt = request.createdAt,
        )
        val fields = event.toRedisStreamFields()
        val publishStartedAt = Instant.now()
        val publishAgeMs = Duration.between(request.createdAt, publishStartedAt).toMillis()
        logger.info(
            "redis_stream_publish_started prefix={} eventId={} eventType={} recordId={} deviceId={} userId={} topic={} pushCreatedAt={} publishStartedAt={} publishAgeMs={} fieldKeys={}",
            properties.streams.pushPrefix,
            fields["eventId"],
            fields["eventType"],
            event.recordId,
            event.deviceId,
            event.userId,
            event.topic,
            request.createdAt,
            publishStartedAt,
            publishAgeMs,
            fields.keys,
        )
        return try {
            val published = publisher.publish(
                null,
                fields,
                RedisStreamPublishOptions(properties.streams.maxLen, true),
            )
            val publishedAt = Instant.now()
            logger.info(
                "redis_stream_publish_succeeded stream={} redisRecordId={} eventId={} eventType={} recordId={} deviceId={} userId={} pushCreatedAt={} publishedAt={} publishAgeMs={}",
                published.streamKey,
                published.recordId,
                fields["eventId"],
                fields["eventType"],
                event.recordId,
                event.deviceId,
                event.userId,
                request.createdAt,
                publishedAt,
                Duration.between(request.createdAt, publishedAt).toMillis(),
            )
            true
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_publish_failed prefix={} eventId={} eventType={} recordId={} deviceId={} userId={} error={}",
                properties.streams.pushPrefix,
                fields["eventId"],
                fields["eventType"],
                event.recordId,
                event.deviceId,
                event.userId,
                error.message,
            )
            false
        }
    }

}
