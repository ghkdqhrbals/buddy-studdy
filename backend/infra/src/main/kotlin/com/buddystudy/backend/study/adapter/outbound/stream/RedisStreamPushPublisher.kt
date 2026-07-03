package com.buddystudy.backend.study.adapter.outbound.stream

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class RedisStreamPushPublisher(
    private val properties: BuddyStudyProperties,
    private val pushPublisher: RedisStreamPublishOperations,
) : QuestionPushPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)

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
            "redis_stream_publish_started streamKey={} eventId={} eventType={} recordId={} deviceId={} userId={} topic={} pushCreatedAt={} publishStartedAt={} publishAgeMs={} fieldKeys={}",
            properties.streams.key,
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
            val published = pushPublisher.publish(properties.streams.key, fields)
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
                "redis_stream_publish_failed streamKey={} eventId={} eventType={} recordId={} deviceId={} userId={} error={}",
                properties.streams.key,
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
