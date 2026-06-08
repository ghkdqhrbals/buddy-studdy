package com.buddystuddy.backend.community.adapter.outbound.stream

import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.common.application.model.QuestionStreamEventType
import com.buddystuddy.backend.utils.toStringMapWithoutNull
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class PublicQuestionReactionRedisStreamPublisher(
    private val properties: BuddyStuddyProperties,
    @Qualifier("viewStreamPublisher") viewPublisherProvider: ObjectProvider<RedisStreamPublisher>,
    @Qualifier("actionStreamPublisher") actionPublisherProvider: ObjectProvider<RedisStreamPublisher>,
) : PublicQuestionReactionPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val viewPublisher: RedisStreamPublisher? = viewPublisherProvider.ifAvailable
    private val actionPublisher: RedisStreamPublisher? = actionPublisherProvider.ifAvailable

    override fun publishViewed(questionId: Long, userId: Long?): Boolean {
        if (!properties.streams.enabled) return false
        val publisher = viewPublisher ?: return false
        val fields = PublicQuestionViewedEvent(questionId = questionId, userId = userId).toStringMapWithoutNull()
        return publish(publisher, properties.streams.viewPrefix, questionId, fields)
    }

    override fun publishLiked(questionId: Long, userId: Long): Boolean =
        publishAction(questionId, QuestionStreamEventType.QUESTION_LIKED, userId)

    override fun publishUnliked(questionId: Long, userId: Long): Boolean =
        publishAction(questionId, QuestionStreamEventType.QUESTION_UNLIKED, userId)

    override fun publishCommented(questionId: Long, userId: Long): Boolean =
        publishAction(questionId, QuestionStreamEventType.QUESTION_COMMENTED, userId)

    fun publishAction(questionId: Long, eventType: String, userId: Long?): Boolean =
        publishAction(questionId, QuestionStreamEventType.valueOf(eventType), userId)

    fun publishAction(questionId: Long, eventType: QuestionStreamEventType, userId: Long?): Boolean {
        if (!properties.streams.enabled) return false
        val publisher = actionPublisher ?: return false
        val fields = PublicQuestionActionEvent(questionId = questionId, eventType = eventType, userId = userId).toStringMapWithoutNull()
        return publish(publisher, properties.streams.actionPrefix, questionId, fields)
    }

    private fun publish(
        publisher: RedisStreamPublisher,
        prefix: String,
        questionId: Long,
        fields: Map<String, String>,
    ): Boolean =
        try {
            val published = publisher.publish(
                questionId.toString(),
                fields,
                RedisStreamPublishOptions(properties.streams.maxLen, true),
            )
            logger.info("redis_stream_published stream={} id={} fields={}", published.streamKey, published.recordId, fields.keys)
            true
        } catch (error: Exception) {
            logger.warn("redis_stream_publish_failed prefix={} error={}", prefix, error.message)
            false
        }
}
