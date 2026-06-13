package com.buddystuddy.backend.community.adapter.outbound.stream

import com.buddystuddy.backend.community.application.port.outbound.QuestionSearchPublishPort
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.study.adapter.outbound.stream.BaseRedisStreamEvent
import com.buddystuddy.utils.toStringMapWithoutNull
import com.buddystuddy.common.application.model.QuestionStreamEventType
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class QuestionSearchStreamPublisher(
    private val properties: BuddyStuddyProperties,
    @Qualifier("questionSearchStreamPublisher") publisherProvider: ObjectProvider<RedisStreamPublisher>,
) : QuestionSearchPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val publisher: RedisStreamPublisher? = publisherProvider.ifAvailable

    init {
        if (properties.streams.enabled) {
            requireNotNull(publisher) { "questionSearchStreamPublisher bean is required when buddystuddy.streams.enabled=true" }
        }
    }

    override fun publishCreated(questionId: Long): Boolean {
        if (!properties.streams.enabled) {
            logger.info("redis_stream_publish_skipped reason=streams_disabled eventType=QUESTION_CREATED questionId={}", questionId)
            return false
        }
        val event = QuestionCreatedSearchEvent(questionId = questionId)
        val fields = event.toStringMapWithoutNull()
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
                fields["eventId"],
                fields["eventType"],
                questionId,
            )
            true
        } catch (error: Exception) {
            logger.warn("redis_stream_publish_failed prefix={} eventType=QUESTION_CREATED questionId={} error={}", properties.streams.questionSearchPrefix, questionId, error.message)
            false
        }
    }
}

data class QuestionCreatedSearchEvent(
    val questionId: Long,
    val createdAt: String = Instant.now().toString(),
    override val eventId: String = java.util.UUID.randomUUID().toString(),
) : BaseRedisStreamEvent(QuestionStreamEventType.QUESTION_CREATED, eventId)
