package com.buddystuddy.backend.community.adapter.inbound.stream

import com.buddystuddy.backend.community.application.service.QuestionSearchSyncManager
import com.redisstream.consumer.ConsumedRedisStreamMessage
import com.redisstream.consumer.RedisStreamXNackMode
import com.redisstream.consumer.StreamConfiguration
import com.redisstream.consumer.StreamListener
import org.slf4j.LoggerFactory

@StreamConfiguration
class QuestionSearchStreamListener(
    private val searchSync: QuestionSearchSyncManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @StreamListener(
        id = "buddystuddy-question-search-listener",
        streamPrefix = "\${QUESTION_SEARCH_STREAM_PREFIX:create-question-v1}",
        groupId = "\${QUESTION_SEARCH_CONSUMER_GROUP_NAME:bs-backend}",
        concurrency = "\${QUESTION_SEARCH_CONSUMER_MEMBER_CONCURRENCY:2}",
        autoStartup = "\${buddystuddy.streams.enabled:true}",
        pollBatchSize = "\${QUESTION_SEARCH_CONSUMER_REDIS_POLL_BATCH_SIZE:50}",
        pollTimeoutMs = "\${QUESTION_SEARCH_CONSUMER_REDIS_POLL_TIMEOUT_MS:3000}",
    )
    fun onQuestionCreated(message: ConsumedRedisStreamMessage) {
        val questionId = message.fields["questionId"]?.toLongOrNull()
        if (questionId == null) {
            logger.warn("question_search_event_ignored reason=missing_question_id stream={} redisRecordId={} fieldKeys={}", message.streamKey, message.recordId, message.fields.keys)
            message.ack()
            return
        }

        try {
            logger.info("question_search_event_started stream={} redisRecordId={} eventId={} eventType={} questionId={}", message.streamKey, message.recordId, message.fields["eventId"], message.fields["eventType"], questionId)
            searchSync.syncQuestion(questionId)
            message.ack()
            logger.info("question_search_event_succeeded stream={} redisRecordId={} eventId={} questionId={}", message.streamKey, message.recordId, message.fields["eventId"], questionId)
        } catch (error: Exception) {
            logger.warn("question_search_event_failed stream={} redisRecordId={} eventId={} questionId={} error={}", message.streamKey, message.recordId, message.fields["eventId"], questionId, error.message)
            message.nack(RedisStreamXNackMode.SILENT, 30_000, false)
        }
    }
}
