package com.buddystuddy.backend.community.adapter.inbound.stream

import com.buddystuddy.domain.QuestionStatsEntity
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
        consume(message) { processViewEvent(message.fields) }
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
        consume(message) { processActionEvent(message.fields) }
    }

    @Transactional
    fun processViewEvent(fields: Map<String, String>) {
        val questionId = fields.questionIdOrNull() ?: return
        increment(questionId) { stats.incrementView(questionId, 1, Instant.now()) }
    }

    @Transactional
    fun processActionEvent(fields: Map<String, String>) {
        val questionId = fields.questionIdOrNull() ?: return
        when (fields["eventType"]) {
            "QUESTION_LIKED" -> increment(questionId) { stats.incrementLike(questionId, 1, Instant.now()) }
            "QUESTION_UNLIKED" -> increment(questionId) { stats.incrementLike(questionId, -1, Instant.now()) }
            "QUESTION_COMMENTED" -> increment(questionId) { stats.incrementComment(questionId, 1, Instant.now()) }
            "QUESTION_COMMENT_DELETED" -> increment(questionId) { stats.incrementComment(questionId, -1, Instant.now()) }
            else -> logger.debug("question_stats_stream_ignored eventType={} fields={}", fields["eventType"], fields.keys)
        }
    }

    private fun consume(message: ConsumedRedisStreamMessage, block: () -> Unit) {
        try {
            block()
            message.ack()
        } catch (error: Exception) {
            logger.warn(
                "question_stats_stream_consume_failed stream={} recordId={} error={}",
                message.streamKey,
                message.recordId,
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

    private fun Map<String, String>.questionIdOrNull(): Long? =
        this["questionId"]?.toLongOrNull()
            ?: this["recordId"]?.toLongOrNull()
}
