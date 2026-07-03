package com.buddystudy.backend.study.adapter.outbound.stream

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.study.application.port.outbound.QuestionCreatedPublishPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class RedisStreamQuestionCreatedPublisher(
    private val properties: BuddyStudyProperties,
    private val publisher: RedisStreamPublishOperations,
) : QuestionCreatedPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun publishQuestionCreated(questionId: Long, language: String, createdAt: Instant): Boolean {
        if (!properties.streams.enabled) {
            logger.info("redis_stream_publish_skipped reason=streams_disabled eventType=QUESTION_CREATED questionId={}", questionId)
            return false
        }
        val event = QuestionCreatedEvent(questionId = questionId, language = language, createdAt = createdAt)
        val fields = event.toRedisStreamFields()
        return try {
            val published = publisher.publish(properties.streams.key, fields)
            logger.info(
                "redis_stream_publish_succeeded stream={} redisRecordId={} eventId={} eventType={} questionId={}",
                published.streamKey,
                published.recordId,
                event.eventId,
                event.eventType,
                questionId,
            )
            true
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_publish_failed streamKey={} eventId={} eventType={} questionId={} error={}",
                properties.streams.key,
                event.eventId,
                event.eventType,
                questionId,
                error.message,
            )
            false
        }
    }
}
