package com.buddystuddy.backend.study.adapter.outbound.stream

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.application.port.outbound.QuestionCreatedPublishPort
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class RedisStreamQuestionCreatedPublisher(
    private val properties: BuddyStuddyProperties,
    @Qualifier("questionCreatedStreamPublisher") publisherProvider: ObjectProvider<RedisStreamPublisher>,
) : QuestionCreatedPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val publisher: RedisStreamPublisher? = publisherProvider.ifAvailable

    init {
        if (properties.streams.enabled) {
            requireNotNull(publisher) { "questionCreatedStreamPublisher bean is required when buddystuddy.streams.enabled=true" }
        }
    }

    override fun publishQuestionCreated(questionId: Long, language: String, createdAt: Instant): Boolean {
        if (!properties.streams.enabled) {
            logger.info("redis_stream_publish_skipped reason=streams_disabled eventType=QUESTION_CREATED questionId={}", questionId)
            return false
        }
        val event = QuestionCreatedEvent(questionId = questionId, language = language, createdAt = createdAt)
        val fields = event.toRedisStreamFields()
        return try {
            val published = requireNotNull(publisher).publish(
                questionId.toString(),
                fields,
                RedisStreamPublishOptions(properties.streams.maxLen, true),
            )
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
                "redis_stream_publish_failed prefix={} eventId={} eventType={} questionId={} error={}",
                properties.streams.createQuestionPrefix,
                event.eventId,
                event.eventType,
                questionId,
                error.message,
            )
            false
        }
    }
}
