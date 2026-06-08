package com.buddystuddy.backend.stream

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.Instant
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

    fun publishPush(fields: Map<String, Any?>): Boolean =
        publishWithStarter(
            publisher = pushPublisher,
            prefix = properties.streams.pushPrefix,
            partitionKey = fields["topic"]?.toString() ?: fields["recordId"]?.toString(),
            fields = fields + event("QUESTION_PUSH_REQUESTED"),
        )

    fun publishQuestionViewed(questionId: Long, userId: Long?): Boolean {
        val fields = mutableMapOf<String, Any?>(
            "questionId" to questionId,
            "userId" to userId,
            "minuteBucket" to Instant.now().epochSecond / 60,
        )
        return publishWithStarter(
            publisher = viewPublisher,
            prefix = properties.streams.viewPrefix,
            partitionKey = questionId.toString(),
            fields = fields + event("CONTENT_VIEWED"),
        )
    }

    fun publishQuestionChanged(questionId: Long, eventType: String, userId: Long?): Boolean {
        val fields = mapOf(
            "questionId" to questionId,
            "userId" to userId,
            "createdAt" to Instant.now().toString(),
        ) + event(eventType)
        return publishWithStarter(
            publisher = actionPublisher,
            prefix = properties.streams.actionPrefix,
            partitionKey = questionId.toString(),
            fields = fields,
        )
    }

    private fun publishWithStarter(
        publisher: RedisStreamPublisher?,
        prefix: String,
        partitionKey: String?,
        fields: Map<String, Any?>,
    ): Boolean {
        if (!properties.streams.enabled) return false
        val publisher = publisher ?: return false
        val normalized = fields
            .filterValues { it != null && it.toString().isNotBlank() }
            .mapValues { it.value.toString() }
        return try {
            val published = publisher.publish(
                partitionKey ?: UUID.randomUUID().toString(),
                normalized,
                RedisStreamPublishOptions(properties.streams.maxLen, true),
            )
            logger.info(
                "redis_stream_published stream={} id={} fields={}",
                published.streamKey,
                published.recordId,
                normalized.keys,
            )
            true
        } catch (error: Exception) {
            logger.warn("redis_stream_publish_failed prefix={} error={}", prefix, error.message)
            false
        }
    }

    private fun event(eventType: String) = mapOf("eventId" to UUID.randomUUID().toString(), "eventType" to eventType)
}
