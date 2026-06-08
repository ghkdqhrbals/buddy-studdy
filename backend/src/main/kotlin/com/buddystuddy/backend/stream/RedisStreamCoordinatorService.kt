package com.buddystuddy.backend.stream

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.redisstream.producer.RedisStreamPublishOptions
import com.redisstream.producer.RedisStreamPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class RedisStreamCoordinatorService(
    private val properties: BuddyStuddyProperties,
    publisherProvider: ObjectProvider<RedisStreamPublisher>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val publisher: RedisStreamPublisher? = publisherProvider.ifAvailable

    fun publishPush(fields: Map<String, Any?>): Boolean =
        publishWithStarter(fields["topic"]?.toString() ?: fields["recordId"]?.toString(), fields + event("QUESTION_PUSH_REQUESTED"))

    fun publishQuestionViewed(questionId: Long, userId: Long?) {
        val fields = mutableMapOf<String, Any?>(
            "questionId" to questionId,
            "userId" to userId,
            "minuteBucket" to Instant.now().epochSecond / 60,
        )
        logger.debug("question_view_event_ready questionId={} userId={} fields={}", questionId, userId, fields + event("CONTENT_VIEWED"))
    }

    fun publishQuestionChanged(questionId: Long, eventType: String, userId: Long?) {
        logger.debug("question_action_event_ready questionId={} eventType={} userId={}", questionId, eventType, userId)
    }

    private fun publishWithStarter(partitionKey: String?, fields: Map<String, Any?>): Boolean {
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
            logger.warn("redis_stream_publish_failed prefix={} error={}", properties.streams.pushPrefix, error.message)
            false
        }
    }

    private fun event(eventType: String) = mapOf("eventId" to UUID.randomUUID().toString(), "eventType" to eventType)
}
