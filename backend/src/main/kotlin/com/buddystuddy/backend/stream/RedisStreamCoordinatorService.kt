package com.buddystuddy.backend.stream

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class RedisStreamCoordinatorService(
    private val properties: BuddyStuddyProperties,
    @Qualifier("pushStreamPublisher") pushPublisherProvider: ObjectProvider<RedisStreamPublisher>,
    @Qualifier("viewStreamPublisher") viewPublisherProvider: ObjectProvider<RedisStreamPublisher>,
    @Qualifier("actionStreamPublisher") actionPublisherProvider: ObjectProvider<RedisStreamPublisher>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val pushPublisher: RedisStreamPublisher? = pushPublisherProvider.ifAvailable
    private val viewPublisher: RedisStreamPublisher? = viewPublisherProvider.ifAvailable
    private val actionPublisher: RedisStreamPublisher? = actionPublisherProvider.ifAvailable

    fun publishPush(fields: Map<String, Any?>): Boolean {
        val event = QuestionPushRequestedEvent(fields)
        return publishWithStarter(
            publisher = pushPublisher,
            prefix = properties.streams.pushPrefix,
            partitionKey = fields["topic"]?.toString() ?: fields["recordId"]?.toString(),
            event = event,
        )
    }

    fun publishQuestionViewed(questionId: Long, userId: Long?): Boolean =
        publishWithStarter(
            publisher = viewPublisher,
            prefix = properties.streams.viewPrefix,
            partitionKey = questionId.toString(),
            event = QuestionViewedEvent(questionId = questionId, userId = userId),
        )

    fun publishQuestionChanged(questionId: Long, eventType: String, userId: Long?): Boolean =
        publishQuestionChanged(questionId, QuestionStreamEventType.valueOf(eventType), userId)

    fun publishQuestionChanged(questionId: Long, eventType: QuestionStreamEventType, userId: Long?): Boolean =
        publishWithStarter(
            publisher = actionPublisher,
            prefix = properties.streams.actionPrefix,
            partitionKey = questionId.toString(),
            event = QuestionActionEvent(
                questionId = questionId,
                eventType = eventType,
                userId = userId,
            ),
        )

    private fun publishWithStarter(
        publisher: RedisStreamPublisher?,
        prefix: String,
        partitionKey: String?,
        event: RedisStreamEvent,
    ): Boolean {
        if (!properties.streams.enabled) return false
        val publisher = publisher ?: return false
        val fields = event.toRedisStreamFields()
        return try {
            val published = publisher.publish(
                partitionKey ?: UUID.randomUUID().toString(),
                fields,
                RedisStreamPublishOptions(properties.streams.maxLen, true),
            )
            logger.info(
                "redis_stream_published stream={} id={} fields={}",
                published.streamKey,
                published.recordId,
                fields.keys,
            )
            true
        } catch (error: Exception) {
            logger.warn("redis_stream_publish_failed prefix={} error={}", prefix, error.message)
            false
        }
    }

    private fun RedisStreamEvent.toRedisStreamFields(): Map<String, String> =
        toStreamMap()
            .filterValues { it != null && it.toString().isNotBlank() }
            .mapValues { (_, value) -> value.toString() }
}
