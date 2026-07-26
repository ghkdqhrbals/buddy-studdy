package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.config.BuddyStudyProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext

data class RedisStreamPublishedMessage(
    val streamKey: String,
    val recordId: String,
)

interface RedisStreamPublishOperations {
    suspend fun publish(streamKey: String, fields: Map<String, String>): RedisStreamPublishedMessage
}

data class RedisStreamMessage(
    val streamKey: String,
    val recordId: String,
    val fields: Map<String, String>,
)

@Component
class RedisStreamPublisher(
    private val redis: ReactiveStringRedisTemplate,
    private val properties: BuddyStudyProperties,
) : RedisStreamPublishOperations {
    override suspend fun publish(streamKey: String, fields: Map<String, String>): RedisStreamPublishedMessage {
        val record = MapRecord.create(streamKey, fields)
        val id = redis.opsForStream<String, String>().add(record)
            .awaitSingle()
        trim(streamKey)
        return RedisStreamPublishedMessage(streamKey = streamKey, recordId = id.value)
    }

    private suspend fun trim(streamKey: String) {
        val maxLen = properties.streams.maxLen
        if (maxLen > 0) {
            redis.opsForStream<String, String>().trim(streamKey, maxLen, true).awaitSingle()
        }
    }
}

@Component
@ConditionalOnProperty(prefix = "buddystudy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class RedisStreamConsumer(
    private val blockingRedis: StringRedisTemplate,
    private val reactiveRedis: ReactiveStringRedisTemplate,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun poll(
        streamKey: String,
        group: String,
        consumer: String,
        count: Long,
        timeout: Duration,
        handler: suspend (RedisStreamMessage) -> Unit,
    ) {
        val records = withContext(Dispatchers.IO) {
            ensureGroup(streamKey, group)
            read(streamKey, group, consumer, count, timeout, ReadOffset.from("0-0")).ifEmpty {
                read(streamKey, group, consumer, count, timeout, ReadOffset.lastConsumed())
            }
        }
        records.forEach { record ->
            val message = RedisStreamMessage(
                streamKey = streamKey,
                recordId = record.id.value,
                fields = record.value.mapKeys { it.key.toString() }.mapValues { it.value.toString() },
            )
            handler(message)
        }
    }

    private fun read(
        streamKey: String,
        group: String,
        consumer: String,
        count: Long,
        timeout: Duration,
        offset: ReadOffset,
    ): List<MapRecord<String, String, String>> =
        blockingRedis.opsForStream<String, String>().read(
            Consumer.from(group, consumer),
            StreamReadOptions.empty()
                .count(count)
                .block(timeout),
            StreamOffset.create(streamKey, offset),
        ).orEmpty()

    suspend fun acknowledge(message: RedisStreamMessage, group: String) {
        reactiveRedis.opsForStream<String, String>()
            .acknowledge(message.streamKey, group, RecordId.of(message.recordId))
            .awaitSingle()
    }

    private fun ensureGroup(streamKey: String, group: String) {
        try {
            blockingRedis.connectionFactory?.connection?.use { connection ->
                connection.execute(
                    "XGROUP",
                    "CREATE".toByteArray(),
                    streamKey.toByteArray(),
                    group.toByteArray(),
                    "0".toByteArray(),
                    "MKSTREAM".toByteArray(),
                )
            }
        } catch (error: Exception) {
            if (error.message?.contains("BUSYGROUP") == true) return
            logger.debug("redis_stream_group_create_ignored stream={} group={} error={}", streamKey, group, error.message)
        }
    }
}
