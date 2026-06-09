package com.buddystuddy.backend.community.adapter.outbound.stream

import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.common.application.model.QuestionStreamEventType
import com.buddystuddy.utils.toStringMapWithoutNull
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class PublicQuestionReactionRedisStreamPublisher(
    private val properties: BuddyStuddyProperties,
    @Qualifier("viewStreamPublisher") viewPublisherProvider: ObjectProvider<RedisStreamPublisher>,
    @Qualifier("actionStreamPublisher") actionPublisherProvider: ObjectProvider<RedisStreamPublisher>,
) : PublicQuestionReactionPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val viewPublisher: RedisStreamPublisher? = viewPublisherProvider.ifAvailable
    private val actionPublisher: RedisStreamPublisher? = actionPublisherProvider.ifAvailable

    init {
        if (properties.streams.enabled) {
            requireNotNull(viewPublisher) { "viewStreamPublisher bean is required when buddystuddy.streams.enabled=true" }
            requireNotNull(actionPublisher) { "actionStreamPublisher bean is required when buddystuddy.streams.enabled=true" }
        }
    }

    override fun publishViewed(questionId: Long, userId: Long?): Boolean {
        if (!properties.streams.enabled) {
            logPublishSkipped("streams_disabled", properties.streams.viewPrefix, "CONTENT_VIEWED", questionId, userId)
            return false
        }
        val publisher = viewPublisher ?: run {
            error("viewStreamPublisher bean is required when buddystuddy.streams.enabled=true")
        }
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
        if (!properties.streams.enabled) {
            logPublishSkipped("streams_disabled", properties.streams.actionPrefix, eventType.name, questionId, userId)
            return false
        }
        val publisher = actionPublisher ?: run {
            error("actionStreamPublisher bean is required when buddystuddy.streams.enabled=true")
        }
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
            logger.info(
                "redis_stream_publish_started prefix={} eventId={} eventType={} partitionKey={} questionId={} userId={} fieldKeys={}",
                prefix,
                fields["eventId"],
                fields["eventType"],
                questionId,
                questionId,
                fields["userId"],
                fields.keys,
            )
            val published = publisher.publish(
                questionId.toString(),
                fields,
                RedisStreamPublishOptions(properties.streams.maxLen, true),
            )
            logger.info(
                "redis_stream_publish_succeeded stream={} redisRecordId={} eventId={} eventType={} partitionKey={} questionId={} userId={}",
                published.streamKey,
                published.recordId,
                fields["eventId"],
                fields["eventType"],
                questionId,
                questionId,
                fields["userId"],
            )
            true
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_publish_failed prefix={} eventId={} eventType={} partitionKey={} questionId={} userId={} error={}",
                prefix,
                fields["eventId"],
                fields["eventType"],
                questionId,
                questionId,
                fields["userId"],
                error.message,
            )
            false
        }

    private fun logPublishSkipped(reason: String, prefix: String, eventType: String, questionId: Long, userId: Long?) {
        logger.info(
            "redis_stream_publish_skipped reason={} prefix={} eventType={} questionId={} userId={}",
            reason,
            prefix,
            eventType,
            questionId,
            userId,
        )
    }
}
