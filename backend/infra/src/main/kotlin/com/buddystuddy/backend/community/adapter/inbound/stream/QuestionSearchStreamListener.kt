package com.buddystuddy.backend.community.adapter.inbound.stream

import com.buddystuddy.backend.community.application.service.QuestionSearchSyncManager
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.redisstream.consumer.ConsumedRedisStreamMessage
import com.redisstream.consumer.RedisStreamXNackMode
import com.redisstream.consumer.StreamConfiguration
import com.redisstream.consumer.StreamListener
import org.slf4j.LoggerFactory
import java.time.Instant

@StreamConfiguration
class QuestionSearchStreamListener(
    private val questionSearch: QuestionSearchSyncManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @StreamListener(
        id = "buddystuddy-question-search-listener",
        streamPrefix = "\${CREATE_QUESTION_STREAM_PREFIX:create-question-v1}",
        groupId = "\${CREATE_QUESTION_CONSUMER_GROUP_NAME:\${CREATE_QUESTION_CONSUMER_GROUP:bs-backend}}",
        concurrency = "\${CREATE_QUESTION_CONSUMER_MEMBER_CONCURRENCY:\${CREATE_QUESTION_CONSUMER_RUNTIME_MAX_CONCURRENCY:2}}",
        autoStartup = "\${buddystuddy.streams.enabled:true}",
        pollBatchSize = "\${CREATE_QUESTION_CONSUMER_REDIS_POLL_BATCH_SIZE:100}",
        pollTimeoutMs = "\${CREATE_QUESTION_CONSUMER_REDIS_POLL_TIMEOUT_MS:3000}",
    )
    fun onQuestionCreated(message: ConsumedRedisStreamMessage) {
        try {
            val payload = QuestionCreatedPayloadParser.toPayload(message.fields)
            logger.debug(
                "redis_stream_consume_started listener={} stream={} redisRecordId={} eventId={} eventType={} questionId={} language={}",
                "buddystuddy-question-search-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                payload.questionId,
                payload.language,
            )
            questionSearch.syncQuestion(payload.questionId)
            message.ack()
            logger.debug(
                "redis_stream_consume_succeeded listener={} stream={} redisRecordId={} eventId={} eventType={} questionId={}",
                "buddystuddy-question-search-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                payload.questionId,
            )
        } catch (error: Exception) {
            logger.warn(
                "redis_stream_consume_failed listener={} stream={} redisRecordId={} eventId={} eventType={} questionId={} error={}",
                "buddystuddy-question-search-listener",
                message.streamKey,
                message.recordId,
                message.fields["eventId"],
                message.fields["eventType"],
                message.fields["questionId"],
                error.message,
            )
            message.nack(RedisStreamXNackMode.SILENT, 30_000, false)
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
