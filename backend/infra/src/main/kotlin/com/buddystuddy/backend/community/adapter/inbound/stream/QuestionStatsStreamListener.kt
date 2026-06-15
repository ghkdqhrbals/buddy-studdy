package com.buddystuddy.backend.community.adapter.inbound.stream

import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.backend.study.adapter.outbound.persistence.QuestionStatsRepository
import com.redisstream.consumer.ConsumedRedisStreamMessage
import com.redisstream.consumer.RedisStreamXNackMode
import com.redisstream.consumer.StreamConfiguration
import com.redisstream.consumer.StreamListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@StreamConfiguration
class QuestionStatsStreamListener(
    private val handler: QuestionStatsStreamEventHandler,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @StreamListener(
        id = "buddystuddy-question-view-listener",
        streamPrefix = "\${VIEW_STREAM_PREFIX:view-content-v1}",
        groupId = "\${VIEW_CONSUMER_GROUP_NAME:\${VIEW_CONSUMER_GROUP:bs-backend}}",
        concurrency = "\${VIEW_CONSUMER_MEMBER_CONCURRENCY:\${VIEW_CONSUMER_RUNTIME_MAX_CONCURRENCY:8}}",
        autoStartup = "\${buddystuddy.streams.enabled:true}",
        pollBatchSize = "\${VIEW_CONSUMER_REDIS_POLL_BATCH_SIZE:100}",
        pollTimeoutMs = "\${VIEW_CONSUMER_REDIS_POLL_TIMEOUT_MS:3000}",
    )
    fun onQuestionViewed(message: ConsumedRedisStreamMessage) {
        consume("buddystuddy-question-view-listener", message) { handler.processViewEvent(message.fields) }
    }

    private fun consume(listenerId: String, message: ConsumedRedisStreamMessage, block: () -> Unit) {
        try {
            logger.debug(
                "redis_stream_consume_started listener={} stream={} redisRecordId={} eventId={} eventType={} questionId={} userId={} fieldKeys={}",
                listenerId,
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["questionId"] ?: message.fields["recordId"],
                message.fields["userId"],
                message.fields.keys,
            )
            block()
            message.ack()
            logger.debug(
                "redis_stream_consume_succeeded listener={} stream={} redisRecordId={} eventId={} eventType={} questionId={} userId={}",
                listenerId,
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["questionId"] ?: message.fields["recordId"],
                message.fields["userId"],
            )
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_consume_failed listener={} stream={} redisRecordId={} eventId={} eventType={} questionId={} userId={} error={}",
                listenerId,
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["questionId"] ?: message.fields["recordId"],
                message.fields["userId"],
                error.message,
            )
            message.nack(RedisStreamXNackMode.SILENT, 30_000, false)
        }
    }
}

@Component
class QuestionStatsStreamEventHandler(
    private val stats: QuestionStatsRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun processViewEvent(fields: Map<String, String>) {
        val questionId = fields.questionIdOrNull() ?: run {
            logger.debug(
                "question_stats_event_ignored reason=missing_question_id eventId={} eventType={} fieldKeys={}",
                fields["eventId"],
                fields["eventType"],
                fields.keys,
            )
            return
        }
        increment(questionId) { stats.incrementView(questionId, 1, Instant.now()) }
        logger.debug(
            "question_stats_event_applied eventId={} eventType={} questionId={} deltaField={}",
            fields["eventId"],
            fields["eventType"] ?: "CONTENT_VIEWED",
            questionId,
            "viewCount",
        )
    }

    private fun increment(questionId: Long, update: () -> Int) {
        if (update() == 0) {
            stats.save(QuestionStatsEntity(questionId = questionId, updatedAt = Instant.now()))
            update()
        }
    }

    private fun logApplied(fields: Map<String, String>, questionId: Long, deltaField: String, delta: Int) {
        logger.debug(
            "question_stats_event_applied eventId={} eventType={} questionId={} userId={} deltaField={} delta={}",
            fields["eventId"],
            fields["eventType"],
            questionId,
            fields["userId"],
            deltaField,
            delta,
        )
    }

    private fun Map<String, String>.questionIdOrNull(): Long? =
        this["questionId"]?.toLongOrNull()
            ?: this["recordId"]?.toLongOrNull()
}
