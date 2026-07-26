package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.admin.stream.application.model.AdminCursorPage
import com.buddystudy.backend.admin.stream.application.model.AdminStreamEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamGroupSummary
import com.buddystudy.backend.admin.stream.application.model.AdminStreamTopicSummary
import com.buddystudy.backend.admin.stream.application.port.outbound.AdminRedisStreamInspectionPort
import com.buddystudy.backend.common.adapter.outbound.security.SensitiveDataRedactor
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import io.lettuce.core.Consumer as LettuceConsumer
import io.lettuce.core.XAutoClaimArgs
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import org.springframework.http.HttpStatus
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.connection.RedisStreamCommands
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext

enum class RedisStreamTopic(val apiName: String) {
    DOMAIN_EVENTS("domain-events"),
    PUSH_EVENTS("push-events"),
}

data class RedisStreamTopicDefinition(
    val topic: RedisStreamTopic,
    val streamKey: String,
    val maxLength: Long,
)

data class RedisStreamSubscription(
    val group: String,
    val consumerPrefix: String,
    val concurrency: Int = 1,
    val count: Long,
    val timeout: Duration,
) {
    fun consumerName(workerIndex: Int): String =
        if (workerIndex == 0) consumerPrefix else "$consumerPrefix-${workerIndex + 1}"
}

data class RedisStreamPublishedMessage(
    val streamKey: String,
    val recordId: String,
)

interface RedisStreamPublishOperations {
    suspend fun publish(topic: RedisStreamTopic, fields: Map<String, String>): RedisStreamPublishedMessage
}

interface RedisStreamConsumerOperations {
    suspend fun acknowledge(message: RedisStreamMessage, group: String)
    suspend fun acknowledgeAndDelete(message: RedisStreamMessage, group: String)

    suspend fun readNew(
        topic: RedisStreamTopic,
        group: String,
        consumer: String,
        count: Long,
        timeout: Duration,
    ): List<RedisStreamMessage>

    suspend fun autoClaim(
        topic: RedisStreamTopic,
        group: String,
        consumer: String,
        minIdleTime: Duration,
        count: Long,
        startId: String,
    ): RedisStreamClaimBatch
}

data class RedisStreamMessage(
    val streamKey: String,
    val recordId: String,
    val fields: Map<String, String>,
)

data class RedisStreamClaimBatch(
    val nextStartId: String,
    val messages: List<RedisStreamMessage>,
)

@Component
class RedisStreamTopicManager(
    private val redis: ReactiveStringRedisTemplate,
    private val blockingRedis: StringRedisTemplate,
    private val redactor: SensitiveDataRedactor,
    properties: BuddyStudyProperties,
) : RedisStreamPublishOperations, RedisStreamConsumerOperations, AdminRedisStreamInspectionPort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val topics = listOf(
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.DOMAIN_EVENTS,
            streamKey = properties.streams.key,
            maxLength = properties.streams.maxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.PUSH_EVENTS,
            streamKey = properties.streams.pushKey,
            maxLength = properties.streams.maxLen.coerceAtLeast(1),
        ),
    )

    fun definition(topic: RedisStreamTopic): RedisStreamTopicDefinition =
        topics.first { it.topic == topic }

    override suspend fun publish(topic: RedisStreamTopic, fields: Map<String, String>): RedisStreamPublishedMessage {
        val definition = definition(topic)
        val record = MapRecord.create(definition.streamKey, fields)
        val options = RedisStreamCommands.XAddOptions.maxlen(definition.maxLength)
            .approximateTrimming(false)
        val id = redis.opsForStream<String, String>().add(record, options)
            .awaitSingle()
        return RedisStreamPublishedMessage(streamKey = definition.streamKey, recordId = id.value)
    }

    suspend fun poll(
        topic: RedisStreamTopic,
        subscription: RedisStreamSubscription,
        handler: suspend (RedisStreamMessage) -> Unit,
    ) = coroutineScope {
        val definition = definition(topic)
        (0 until subscription.concurrency.coerceIn(1, MAX_CONCURRENCY)).map { workerIndex ->
            async {
                pollWorker(
                    definition = definition,
                    subscription = subscription,
                    consumerName = subscription.consumerName(workerIndex),
                    handler = handler,
                )
            }
        }.awaitAll()
        Unit
    }

    override suspend fun acknowledge(message: RedisStreamMessage, group: String) {
        redis.opsForStream<String, String>()
            .acknowledge(message.streamKey, group, RecordId.of(message.recordId))
            .awaitSingle()
    }

    override suspend fun acknowledgeAndDelete(message: RedisStreamMessage, group: String) {
        acknowledge(message, group)
        redis.opsForStream<String, String>()
            .delete(message.streamKey, RecordId.of(message.recordId))
            .awaitSingle()
    }

    override suspend fun readNew(
        topic: RedisStreamTopic,
        group: String,
        consumer: String,
        count: Long,
        timeout: Duration,
    ): List<RedisStreamMessage> = withContext(Dispatchers.IO) {
        val definition = definition(topic)
        ensureGroup(definition.streamKey, group)
        read(
            streamKey = definition.streamKey,
            group = group,
            consumer = consumer,
            count = count,
            timeout = timeout,
            offset = ReadOffset.lastConsumed(),
        ).map { it.toMessage(definition.streamKey) }
    }

    override suspend fun autoClaim(
        topic: RedisStreamTopic,
        group: String,
        consumer: String,
        minIdleTime: Duration,
        count: Long,
        startId: String,
    ): RedisStreamClaimBatch = withContext(Dispatchers.IO) {
        val definition = definition(topic)
        ensureGroup(definition.streamKey, group)
        val connectionFactory = requireNotNull(blockingRedis.connectionFactory) {
            "Redis connection factory is required for XAUTOCLAIM."
        }
        connectionFactory.connection.use { connection ->
            @Suppress("UNCHECKED_CAST")
            val commands = connection.nativeConnection as? RedisClusterAsyncCommands<ByteArray, ByteArray>
                ?: error("XAUTOCLAIM requires a Lettuce Redis connection.")
            val arguments = XAutoClaimArgs<ByteArray>()
                .consumer(
                    LettuceConsumer.from(
                        group.toByteArray(Charsets.UTF_8),
                        consumer.toByteArray(Charsets.UTF_8),
                    ),
                )
                .minIdleTime(minIdleTime)
                .startId(startId)
                .count(count.coerceAtLeast(1))
            val claimed = commands.xautoclaim(
                definition.streamKey.toByteArray(Charsets.UTF_8),
                arguments,
            ).get()
            RedisStreamClaimBatch(
                nextStartId = claimed.id,
                messages = claimed.messages.map { record ->
                    RedisStreamMessage(
                        streamKey = record.stream.toString(Charsets.UTF_8),
                        recordId = record.id,
                        fields = record.body
                            .mapKeys { it.key.toString(Charsets.UTF_8) }
                            .mapValues { it.value.toString(Charsets.UTF_8) },
                    )
                },
            )
        }
    }

    override suspend fun topics(): List<AdminStreamTopicSummary> = withContext(Dispatchers.IO) {
        topics.map { definition ->
            runCatching {
                val operations = blockingRedis.opsForStream<String, String>()
                val info = operations.info(definition.streamKey)
                val groups = operations.groups(definition.streamKey).map { group ->
                    AdminStreamGroupSummary(
                        name = group.groupName(),
                        consumers = group.consumerCount(),
                        pending = group.pendingCount(),
                        lastDeliveredId = group.lastDeliveredId(),
                    )
                }.toList()
                AdminStreamTopicSummary(
                    topic = definition.topic.apiName,
                    streamKey = definition.streamKey,
                    maxLength = definition.maxLength,
                    length = info.streamLength(),
                    firstEntryId = info.firstEntryId(),
                    lastEntryId = info.lastEntryId(),
                    groups = groups,
                )
            }.getOrElse { error ->
                logger.debug(
                    "redis_stream_inspection_empty topic={} stream={} error={}",
                    definition.topic.apiName,
                    definition.streamKey,
                    error.message,
                )
                AdminStreamTopicSummary(
                    topic = definition.topic.apiName,
                    streamKey = definition.streamKey,
                    maxLength = definition.maxLength,
                    length = 0,
                    firstEntryId = null,
                    lastEntryId = null,
                    groups = emptyList(),
                )
            }
        }
    }

    override suspend fun entries(
        topic: String,
        cursor: String?,
        limit: Int,
        eventType: String?,
    ): AdminCursorPage<AdminStreamEntry> = withContext(Dispatchers.IO) {
        val definition = topicDefinition(topic)
        val upperBound = cursor ?: "+"
        val fetchCount = if (eventType == null) {
            limit + 1
        } else {
            definition.maxLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val records = blockingRedis.opsForStream<String, String>()
            .reverseRange(
                definition.streamKey,
                Range.closed("-", upperBound),
                Limit.limit().count(fetchCount),
            )
            .orEmpty()
            .asSequence()
            .filterNot { cursor != null && it.id.value == cursor }
            .map(::adminEntry)
            .filter { eventType == null || it.eventType == eventType }
            .take(limit + 1)
            .toList()
        AdminCursorPage(
            items = records.take(limit),
            nextCursor = records.take(limit).lastOrNull()?.id?.takeIf { records.size > limit },
            hasMore = records.size > limit,
            limit = limit,
        )
    }

    override suspend fun entry(topic: String, entryId: String): AdminStreamEntry? = withContext(Dispatchers.IO) {
        val definition = topicDefinition(topic)
        blockingRedis.opsForStream<String, String>()
            .reverseRange(
                definition.streamKey,
                Range.closed(entryId, entryId),
                Limit.limit().count(1),
            )
            .orEmpty()
            .firstOrNull()
            ?.let(::adminEntry)
    }

    private fun topicDefinition(topic: String): RedisStreamTopicDefinition =
        topics.firstOrNull { it.topic.apiName == topic }
            ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RESOURCE_NOT_FOUND,
                "Redis Stream topic '$topic' was not found.",
            )

    private fun adminEntry(record: MapRecord<String, String, String>): AdminStreamEntry {
        val fields = redactor.fields(
            record.value
                .mapKeys { it.key.toString() }
                .mapValues { it.value.toString() },
        )
        return AdminStreamEntry(
            id = record.id.value,
            eventType = fields["eventType"],
            eventId = fields["eventId"],
            recordId = fields["recordId"] ?: fields["questionId"],
            userId = fields["userId"],
            deviceId = fields["deviceId"],
            fields = fields,
        )
    }

    private suspend fun pollWorker(
        definition: RedisStreamTopicDefinition,
        subscription: RedisStreamSubscription,
        consumerName: String,
        handler: suspend (RedisStreamMessage) -> Unit,
    ) {
        val records = withContext(Dispatchers.IO) {
            ensureGroup(definition.streamKey, subscription.group)
            read(
                definition.streamKey,
                subscription.group,
                consumerName,
                subscription.count,
                subscription.timeout,
                ReadOffset.from("0-0"),
            ).ifEmpty {
                read(
                    definition.streamKey,
                    subscription.group,
                    consumerName,
                    subscription.count,
                    subscription.timeout,
                    ReadOffset.lastConsumed(),
                )
            }
        }
        records.forEach { record ->
            handler(record.toMessage(definition.streamKey))
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

    private fun ensureGroup(streamKey: String, group: String) {
        try {
            blockingRedis.opsForStream<String, String>()
                .createGroup(streamKey, ReadOffset.from("0-0"), group)
        } catch (error: Exception) {
            if (error.message?.contains("BUSYGROUP") == true) return
            logger.debug("redis_stream_group_create_ignored stream={} group={} error={}", streamKey, group, error.message)
        }
    }

    private fun MapRecord<String, String, String>.toMessage(streamKey: String): RedisStreamMessage =
        RedisStreamMessage(
            streamKey = streamKey,
            recordId = id.value,
            fields = value.mapKeys { it.key.toString() }.mapValues { it.value.toString() },
        )

    private companion object {
        const val MAX_CONCURRENCY = 32
    }
}
