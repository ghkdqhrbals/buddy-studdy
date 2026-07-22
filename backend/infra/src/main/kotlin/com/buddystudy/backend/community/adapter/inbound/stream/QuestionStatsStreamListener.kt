package com.buddystudy.backend.community.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamConsumer
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.ApplicationCoroutineScope
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class QuestionStatsStreamListener(
    private val properties: BuddyStudyProperties,
    private val consumer: RedisStreamConsumer,
    private val handler: QuestionStatsStreamEventHandler,
    private val coroutineScope: ApplicationCoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val group = "bs-backend-view"
    private val consumerName = "view-${java.net.InetAddress.getLocalHost().hostName}"
    private val eventType = "CONTENT_VIEWED"

    @Scheduled(fixedDelayString = "\${VIEW_CONSUMER_POLL_DELAY_MS:1000}")
    fun pollQuestionViews() {
        consumer.poll(properties.streams.key, group, consumerName, 100, Duration.ofMillis(3000)) {
            coroutineScope.launch { onQuestionViewed(it) }
        }
    }

    suspend fun onQuestionViewed(message: RedisStreamMessage) {
        if (message.fields["eventType"] != eventType) {
            consumer.acknowledge(message, group)
            return
        }
        consume("buddystudy-question-view-listener", message) { handler.processViewEvent(message.fields) }
    }

    private suspend fun consume(listenerId: String, message: RedisStreamMessage, block: suspend () -> Unit) {
        logger.info("Consuming $listenerId")
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
            consumer.acknowledge(message, group)
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
        }
    }
}

@Component
class QuestionStatsStreamEventHandler(
    private val stats: QuestionStatsPort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun processViewEvent(fields: Map<String, String>) {
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

    private suspend fun increment(questionId: Long, update: suspend () -> Int) {
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
