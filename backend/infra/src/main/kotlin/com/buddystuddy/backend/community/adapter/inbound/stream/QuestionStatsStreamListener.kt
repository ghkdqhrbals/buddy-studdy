package com.buddystuddy.backend.community.adapter.inbound.stream

import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.backend.study.adapter.outbound.persistence.QuestionStatsRepository
import com.redisstream.consumer.ConsumedRedisStreamMessage
import com.redisstream.consumer.RedisStreamXNackMode
import com.redisstream.consumer.StreamListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class QuestionStatsStreamListener(
    private val stats: QuestionStatsRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @StreamListener(
        id = "buddystuddy-question-view-listener",
        streamPrefix = "\${buddystuddy.streams.view-prefix:bs-view-content-v1}",
        groupId = "bs-view-workers",
        concurrency = "2",
        autoStartup = "\${buddystuddy.streams.enabled:true}",
        pollBatchSize = "100",
        pollTimeoutMs = "3000",
    )
    fun onQuestionViewed(message: ConsumedRedisStreamMessage) {
        consume("buddystuddy-question-view-listener", message) { processViewEvent(message.fields) }
    }

    @StreamListener(
        id = "buddystuddy-question-action-listener",
        streamPrefix = "\${buddystuddy.streams.action-prefix:bs-question-action-v1}",
        groupId = "bs-question-action-workers",
        concurrency = "2",
        autoStartup = "\${buddystuddy.streams.enabled:true}",
        pollBatchSize = "100",
        pollTimeoutMs = "3000",
    )
    fun onQuestionAction(message: ConsumedRedisStreamMessage) {
        consume("buddystuddy-question-action-listener", message) { processActionEvent(message.fields) }
    }

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
                increment(questionId) { stats.incrementLike(questionId, 1, Instant.now()) }
                logApplied(fields, questionId, "likeCount", 1)
            }
            "QUESTION_UNLIKED" -> {
                increment(questionId) { stats.incrementLike(questionId, -1, Instant.now()) }
                logApplied(fields, questionId, "likeCount", -1)
            }
            "QUESTION_COMMENTED" -> {
                increment(questionId) { stats.incrementComment(questionId, 1, Instant.now()) }
                logApplied(fields, questionId, "commentCount", 1)
            }
            "QUESTION_COMMENT_DELETED" -> {
                increment(questionId) { stats.incrementComment(questionId, -1, Instant.now()) }
                logApplied(fields, questionId, "commentCount", -1)
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
