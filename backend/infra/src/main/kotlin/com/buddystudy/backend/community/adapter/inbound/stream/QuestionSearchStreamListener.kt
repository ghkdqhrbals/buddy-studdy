package com.buddystudy.backend.community.adapter.inbound.stream

import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamConsumer
import com.buddystudy.backend.common.adapter.outbound.redis.RedisStreamMessage
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.ApplicationCoroutineScope
import com.buddystudy.backend.community.application.service.QuestionSearchSyncManager
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class QuestionSearchStreamListener(
    private val properties: BuddyStudyProperties,
    private val consumer: RedisStreamConsumer,
    private val questionSearch: QuestionSearchSyncManager,
    private val coroutineScope: ApplicationCoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val group = "bs-backend-question-search"
    private val consumerName = "question-search-${java.net.InetAddress.getLocalHost().hostName}"
    private val eventType = "QUESTION_CREATED"

    @Scheduled(fixedDelayString = "\${CREATE_QUESTION_CONSUMER_POLL_DELAY_MS:1000}")
    fun pollCreatedQuestions() {
        consumer.poll(properties.streams.key, group, consumerName, 100, Duration.ofMillis(3000)) {
            coroutineScope.launch { onQuestionCreated(it) }
        }
    }

    suspend fun onQuestionCreated(message: RedisStreamMessage) {
        try {
            if (message.fields["eventType"] != eventType) {
                consumer.acknowledge(message, group)
                return
            }

            logger.info("question created consuming {}", message)
            val payload = QuestionCreatedPayloadParser.toPayload(message.fields)
            logger.debug(
                "redis_stream_consume_started listener={} stream={} redisRecordId={} eventId={} eventType={} questionId={} language={}",
                "buddystudy-question-search-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                payload.questionId,
                payload.language,
            )
            questionSearch.indexCreatedQuestion(payload.questionId)
            consumer.acknowledge(message, group)
            logger.debug(
                "redis_stream_consume_succeeded listener={} stream={} redisRecordId={} eventId={} eventType={} questionId={}",
                "buddystudy-question-search-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                payload.questionId,
            )
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_consume_failed listener={} stream={} redisRecordId={} eventId={} eventType={} questionId={} error={}",
                "buddystudy-question-search-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["questionId"],
                error.message,
            )
        }
    }
}

private object QuestionCreatedPayloadParser {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    fun toPayload(fields: Map<String, String>): QuestionCreatedPayload {
        fields["payload"]?.takeIf(String::isNotBlank)?.let {
            return mapper.readValue(it)
        }
        return QuestionCreatedPayload(
            questionId = fields["questionId"]?.toLongOrNull() ?: throw IllegalArgumentException("questionId is required."),
            language = fields["language"] ?: "ko",
            createdAt = fields["createdAt"]?.let(Instant::parse) ?: Instant.now(),
        )
    }
}

private data class QuestionCreatedPayload(
    val questionId: Long,
    val language: String,
    val createdAt: Instant,
)
