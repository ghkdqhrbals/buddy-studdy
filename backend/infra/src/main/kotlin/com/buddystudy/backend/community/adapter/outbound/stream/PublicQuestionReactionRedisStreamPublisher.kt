package com.buddystudy.backend.community.adapter.outbound.stream

import com.buddystudy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionViewLocalization
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamPublishOperations
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamTopic
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.utils.toStringMapWithoutNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class PublicQuestionReactionRedisStreamPublisher(
    private val properties: BuddyStudyProperties,
    private val viewPublisher: RedisStreamPublishOperations,
) : PublicQuestionReactionPublishPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun publishViewed(
        questionId: Long,
        userId: Long?,
        localization: PublicQuestionViewLocalization?,
    ): Boolean {
        if (!properties.streams.enabled) {
            logPublishSkipped("streams_disabled", properties.streams.key, "CONTENT_VIEWED", questionId, userId)
            return false
        }
        val fields = PublicQuestionViewedEvent(
            questionId = questionId,
            userId = userId,
            translationState = localization?.translationState,
            translationLanguage = localization?.translationLanguage,
            translationReason = localization?.translationReason,
            requestId = localization?.requestId,
            questionSourceLanguage = localization?.questionSourceLanguage,
            questionDisplayLanguage = localization?.questionDisplayLanguage,
            answerSourceLanguage = localization?.answerSourceLanguage,
            answerDisplayLanguage = localization?.answerDisplayLanguage,
            aiResponseSourceLanguage = localization?.aiResponseSourceLanguage,
            aiResponseDisplayLanguage = localization?.aiResponseDisplayLanguage,
        ).toStringMapWithoutNull()
        return publish(properties.streams.key, questionId, fields)
    }

    private suspend fun publish(
        streamKey: String,
        questionId: Long,
        fields: Map<String, String>,
    ): Boolean =
        try {
            logger.debug(
                "redis_stream_publish_started streamKey={} eventId={} eventType={} partitionKey={} questionId={} userId={} fieldKeys={}",
                streamKey,
                fields["eventId"],
                fields["eventType"],
                questionId,
                questionId,
                fields["userId"],
                fields.keys,
            )
            val published = viewPublisher.publish(RedisStreamTopic.DOMAIN_EVENTS, fields)
            logger.debug(
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
                "redis_stream_publish_failed streamKey={} eventId={} eventType={} partitionKey={} questionId={} userId={} error={}",
                streamKey,
                fields["eventId"],
                fields["eventType"],
                questionId,
                questionId,
                fields["userId"],
                error.message,
            )
            false
        }

    private fun logPublishSkipped(reason: String, streamKey: String, eventType: String, questionId: Long, userId: Long?) {
        logger.debug(
            "redis_stream_publish_skipped reason={} streamKey={} eventType={} questionId={} userId={}",
            reason,
            streamKey,
            eventType,
            questionId,
            userId,
        )
    }
}
