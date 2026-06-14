package com.buddystuddy.backend.community.adapter.inbound.stream

import com.buddystuddy.backend.community.application.port.outbound.QuestionLikePort
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
        streamPrefix = "\${VIEW_STREAM_PREFIX:bs-view-content-v1}",
        groupId = "\${VIEW_CONSUMER_GROUP_NAME:\${VIEW_CONSUMER_GROUP:bs-backend}}",
        concurrency = "\${VIEW_CONSUMER_MEMBER_CONCURRENCY:\${VIEW_CONSUMER_RUNTIME_MAX_CONCURRENCY:8}}",
        autoStartup = "\${buddystuddy.streams.enabled:true}",
        pollBatchSize = "\${VIEW_CONSUMER_REDIS_POLL_BATCH_SIZE:100}",
        pollTimeoutMs = "\${VIEW_CONSUMER_REDIS_POLL_TIMEOUT_MS:3000}",
    )
    fun onQuestionViewed(message: ConsumedRedisStreamMessage) {
        consume("buddystuddy-question-view-listener", message) { handler.processViewEvent(message.fields) }
    }

    @StreamListener(
        id = "buddystuddy-question-action-listener",
        streamPrefix = "\${ACTION_STREAM_PREFIX:bs-question-action-v1}",
        groupId = "\${ACTION_CONSUMER_GROUP_NAME:\${ACTION_CONSUMER_GROUP:bs-backend}}",
        concurrency = "\${ACTION_CONSUMER_MEMBER_CONCURRENCY:\${ACTION_CONSUMER_RUNTIME_MAX_CONCURRENCY:2}}",
        autoStartup = "\${buddystuddy.streams.enabled:true}",
        pollBatchSize = "\${ACTION_CONSUMER_REDIS_POLL_BATCH_SIZE:100}",
        pollTimeoutMs = "\${ACTION_CONSUMER_REDIS_POLL_TIMEOUT_MS:3000}",
    )
    fun onQuestionAction(message: ConsumedRedisStreamMessage) {
        consume("buddystuddy-question-action-listener", message) { handler.processActionEvent(message.fields) }
    }

    private fun consume(listenerId: String, message: ConsumedRedisStreamMessage, block: () -> Unit) {
        try {
            logger.info(
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
            logger.info(
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
    private val likes: QuestionLikePort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun processViewEvent(fields: Map<String, String>) {
        val questionId = fields.questionIdOrNull() ?: run {
            logger.info(
                "question_stats_event_ignored reason=missing_question_id eventId={} eventType={} fieldKeys={}",
                fields["eventId"],
                fields["eventType"],
                fields.keys,
            )
            return
        }
        increment(questionId) { stats.incrementView(questionId, 1, Instant.now()) }
        logger.info(
            "question_stats_event_applied eventId={} eventType={} questionId={} deltaField={}",
            fields["eventId"],
            fields["eventType"] ?: "CONTENT_VIEWED",
            questionId,
            "viewCount",
        )
    }

    @Transactional
    fun processActionEvent(fields: Map<String, String>) {
        val questionId = fields.questionIdOrNull() ?: run {
            logger.info(
                "question_stats_event_ignored reason=missing_question_id eventId={} eventType={} fieldKeys={}",
                fields["eventId"],
                fields["eventType"],
                fields.keys,
            )
            return
        }
        when (fields["eventType"]) {
            "QUESTION_LIKED" -> {
                synchronizeLikeCount(fields, questionId)
            }
            "QUESTION_UNLIKED" -> {
                synchronizeLikeCount(fields, questionId)
            }
            "QUESTION_COMMENTED", "QUESTION_COMMENT_DELETED" -> {
                logger.info(
                    "question_stats_event_ignored reason=comment_count_updated_synchronously eventId={} eventType={} questionId={}",
                    fields["eventId"],
                    fields["eventType"],
                    questionId,
                )
            }
            else -> logger.info(
                "question_stats_event_ignored reason=unknown_event_type eventId={} eventType={} questionId={} fieldKeys={}",
                fields["eventId"],
                fields["eventType"],
                questionId,
                fields.keys,
            )
        }
    }

    private fun synchronizeLikeCount(fields: Map<String, String>, questionId: Long) {
        val likeCount = likes.countByQuestionId(questionId).toInt()
        overwriteLikeCount(questionId, likeCount)
        logger.info(
            "question_stats_event_applied eventId={} eventType={} questionId={} userId={} field={} value={}",
            fields["eventId"],
            fields["eventType"],
            questionId,
            fields["userId"],
            "likeCount",
            likeCount,
        )
    }

    private fun overwriteLikeCount(questionId: Long, likeCount: Int) {
        val now = Instant.now()
        if (stats.setLikeCount(questionId, likeCount, now) == 0) {
            stats.save(QuestionStatsEntity(questionId = questionId, likeCount = likeCount, updatedAt = now))
        }
    }

    private fun increment(questionId: Long, update: () -> Int) {
        if (update() == 0) {
            stats.save(QuestionStatsEntity(questionId = questionId, updatedAt = Instant.now()))
            update()
        }
    }

    private fun logApplied(fields: Map<String, String>, questionId: Long, deltaField: String, delta: Int) {
        logger.info(
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
