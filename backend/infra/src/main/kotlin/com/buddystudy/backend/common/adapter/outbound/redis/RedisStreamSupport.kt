package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.admin.stream.application.model.AdminCursorPage
import com.buddystudy.backend.admin.stream.application.model.AdminStreamEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamConsumerSummary
import com.buddystudy.backend.admin.stream.application.model.AdminStreamGroupSummary
import com.buddystudy.backend.admin.stream.application.model.AdminStreamInspectionError
import com.buddystudy.backend.admin.stream.application.model.AdminStreamPendingEntry
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
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

enum class RedisStreamTopic(
    val apiName: String,
    val legacy: Boolean = false,
) {
    NOTIFICATION_MESSAGE_REQUESTED("notification.message.requested.v1"),
    IDENTITY_ACCOUNT_WITHDRAWN("identity.account.withdrawn.v1"),
    STUDY_ANSWER_GRADING_REQUESTED("study.answer-grading.requested.v1"),
    STUDY_QUESTION_GENERATION_REQUESTED("study.question-generation.requested.v1"),
    STUDY_QUESTION_GENERATED("study.question.generated.v1"),
    LOCALIZATION_CONTENT_TRANSLATION_REQUESTED("localization.content-translation.requested.v1"),
    NOTIFICATION_QUESTION_PUSH_REQUESTED("notification.question-push.requested.v1"),
    COMMUNITY_QUESTION_VIEWED("community.question.viewed.v1"),

    LEGACY_DOMAIN_EVENTS("legacy-domain-events", legacy = true),
    LEGACY_QUESTION_GENERATION("legacy-question-generation", legacy = true),
    LEGACY_QUESTION_GENERATED("legacy-question-generated", legacy = true),
    LEGACY_CONTENT_TRANSLATION("legacy-content-translation", legacy = true),
    LEGACY_PUSH_EVENTS("legacy-push-events", legacy = true),
    NONE("none"),
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
    suspend fun exists(topic: RedisStreamTopic): Boolean = true
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
            topic = RedisStreamTopic.NOTIFICATION_MESSAGE_REQUESTED,
            streamKey = properties.streams.notificationRequestedKey,
            maxLength = properties.streams.notificationRequestedMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.IDENTITY_ACCOUNT_WITHDRAWN,
            streamKey = properties.streams.accountWithdrawnKey,
            maxLength = properties.streams.accountWithdrawnMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.STUDY_ANSWER_GRADING_REQUESTED,
            streamKey = properties.streams.answerGradingRequestedKey,
            maxLength = properties.streams.answerGradingRequestedMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.STUDY_QUESTION_GENERATION_REQUESTED,
            streamKey = properties.streams.questionGenerationRequestedKey,
            maxLength = properties.streams.questionGenerationRequestedMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.STUDY_QUESTION_GENERATED,
            streamKey = properties.streams.questionGeneratedKey,
            maxLength = properties.streams.questionGeneratedMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.LOCALIZATION_CONTENT_TRANSLATION_REQUESTED,
            streamKey = properties.streams.contentTranslationRequestedKey,
            maxLength = properties.streams.contentTranslationRequestedMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.NOTIFICATION_QUESTION_PUSH_REQUESTED,
            streamKey = properties.streams.questionPushRequestedKey,
            maxLength = properties.streams.questionPushRequestedMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.COMMUNITY_QUESTION_VIEWED,
            streamKey = properties.streams.questionViewedKey,
            maxLength = properties.streams.questionViewedMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.LEGACY_DOMAIN_EVENTS,
            streamKey = properties.streams.legacyDomainKey,
            maxLength = properties.streams.legacyMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.LEGACY_QUESTION_GENERATION,
            streamKey = properties.streams.legacyQuestionGenerationKey,
            maxLength = properties.streams.legacyMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.LEGACY_QUESTION_GENERATED,
            streamKey = properties.streams.legacyQuestionGeneratedKey,
            maxLength = properties.streams.legacyMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.LEGACY_CONTENT_TRANSLATION,
            streamKey = properties.streams.legacyContentTranslationKey,
            maxLength = properties.streams.legacyMaxLen.coerceAtLeast(1),
        ),
        RedisStreamTopicDefinition(
            topic = RedisStreamTopic.LEGACY_PUSH_EVENTS,
            streamKey = properties.streams.legacyPushKey,
            maxLength = properties.streams.legacyMaxLen.coerceAtLeast(1),
        ),
    )

    init {
        val activeDefinitions = topics.filterNot { it.topic.legacy }
        require(activeDefinitions.map { it.streamKey }.distinct().size == activeDefinitions.size) {
            "Active Redis Stream keys must be unique."
        }
        activeDefinitions.forEach { definition ->
            require(ACTIVE_STREAM_KEY.matches(definition.streamKey)) {
                "Redis Stream key '${definition.streamKey}' must follow " +
                    "<business-domain>.<data-type>.<event-type>.<version>."
            }
        }
    }

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

    override suspend fun exists(topic: RedisStreamTopic): Boolean = withContext(Dispatchers.IO) {
        blockingRedis.hasKey(definition(topic).streamKey) == true
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
        redis.execute(
            ACKNOWLEDGE_AND_DELETE,
            listOf(message.streamKey),
            listOf(group, message.recordId),
        ).next().awaitSingle()
    }

    override suspend fun readNew(
        topic: RedisStreamTopic,
        group: String,
        consumer: String,
        count: Long,
        timeout: Duration,
    ): List<RedisStreamMessage> = runInterruptible(Dispatchers.IO) {
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
        topics
            .filter { !it.topic.legacy || blockingRedis.hasKey(it.streamKey) == true }
            .map { definition ->
                inspectTopic(definition)
            }
    }

    private fun inspectTopic(definition: RedisStreamTopicDefinition): AdminStreamTopicSummary {
        val operations = blockingRedis.opsForStream<String, String>()
        val errors = mutableListOf<AdminStreamInspectionError>()
        val info = inspect(definition, "XINFO STREAM", errors) {
            operations.info(definition.streamKey)
        }
        val length = info?.streamLength()
            ?: inspect(definition, "XLEN", errors) {
                operations.size(definition.streamKey)
            }
            ?: 0L
        val groupInfos = inspect(definition, "XINFO GROUPS", errors) {
            operations.groups(definition.streamKey).toList()
        }.orEmpty()
        val groups = groupInfos.map { group ->
            val groupName = group.groupName()
            val groupErrors = mutableListOf<AdminStreamInspectionError>()
            val lastDeliveredId = inspect(definition, "XINFO GROUPS offset", groupErrors, groupName) {
                group.lastDeliveredId()
            }
            val pendingSummary = inspect(definition, "XPENDING summary", groupErrors, groupName) {
                operations.pending(definition.streamKey, groupName)
            }
            val pendingSample = if (group.pendingCount() > 0) {
                inspect(definition, "XPENDING range", groupErrors, groupName) {
                    operations.pending(
                        definition.streamKey,
                        groupName,
                        Range.unbounded<String>(),
                        PENDING_SUMMARY_SAMPLE_LIMIT,
                    ).toList()
                }.orEmpty()
            } else {
                emptyList()
            }
            val consumers = inspect(definition, "XINFO CONSUMERS", groupErrors, groupName) {
                operations.consumers(definition.streamKey, groupName)
                    .map { consumer ->
                        AdminStreamConsumerSummary(
                            name = consumer.consumerName(),
                            pending = consumer.pendingCount(),
                            idleMs = consumer.idleTimeMs(),
                            inactiveMs = consumer.raw.longValue("inactive"),
                        )
                    }
                    .toList()
            }.orEmpty()
            AdminStreamGroupSummary(
                name = groupName,
                consumers = group.consumerCount(),
                pending = group.pendingCount(),
                lastDeliveredId = lastDeliveredId,
                entriesRead = group.raw.longValue("entries-read"),
                lag = group.raw.longValue("lag"),
                pendingMinId = if (group.pendingCount() > 0) {
                    inspect(definition, "XPENDING minimum ID", groupErrors, groupName) {
                        pendingSummary?.minMessageId()
                    }
                } else {
                    null
                },
                pendingMaxId = if (group.pendingCount() > 0) {
                    inspect(definition, "XPENDING maximum ID", groupErrors, groupName) {
                        pendingSummary?.maxMessageId()
                    }
                } else {
                    null
                },
                oldestPendingIdleMs = pendingSample.maxOfOrNull {
                    it.elapsedTimeSinceLastDelivery.toMillis()
                },
                maxDeliveryCount = pendingSample.maxOfOrNull {
                    it.totalDeliveryCount
                } ?: 0,
                maxRetryCount = pendingSample.maxOfOrNull {
                    (it.totalDeliveryCount - 1).coerceAtLeast(0)
                } ?: 0,
                pendingSampleTruncated = group.pendingCount() > pendingSample.size,
                consumerDetails = consumers,
                inspectionErrors = groupErrors,
            )
        }
        return AdminStreamTopicSummary(
            topic = definition.topic.apiName,
            streamKey = definition.streamKey,
            maxLength = definition.maxLength,
            length = length,
            firstEntryId = info?.takeIf { length > 0 }?.let {
                inspect(definition, "XINFO STREAM first entry", errors) { it.firstEntryId() }
            },
            lastEntryId = info?.takeIf { length > 0 }?.let {
                inspect(definition, "XINFO STREAM last entry", errors) { it.lastEntryId() }
            },
            groups = groups,
            inspectionErrors = errors,
        )
    }

    private fun <T> inspect(
        definition: RedisStreamTopicDefinition,
        operation: String,
        errors: MutableList<AdminStreamInspectionError>,
        group: String? = null,
        query: () -> T,
    ): T? = runCatching(query).getOrElse { error ->
        val message = error.message?.take(240) ?: error.javaClass.simpleName
        errors += AdminStreamInspectionError(operation = operation, message = message)
        logger.warn(
            "redis_stream_inspection_failed topic={} stream={} group={} operation={} errorType={} error={}",
            definition.topic.apiName,
            definition.streamKey,
            group ?: "-",
            operation,
            error.javaClass.name,
            message,
        )
        null
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

    override suspend fun pending(
        topic: String,
        group: String,
        cursor: String?,
        limit: Int,
    ): AdminCursorPage<AdminStreamPendingEntry> = withContext(Dispatchers.IO) {
        val definition = topicDefinition(topic)
        val operations = blockingRedis.opsForStream<String, String>()
        val groupExists = operations.groups(definition.streamKey).any { it.groupName() == group }
        if (!groupExists) {
            throw ApiException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RESOURCE_NOT_FOUND,
                "Redis Stream consumer group '$group' was not found.",
            )
        }
        val lowerBound = cursor ?: "-"
        val messages = operations.pending(
            definition.streamKey,
            group,
            Range.closed(lowerBound, "+"),
            limit + 1L,
        ).asSequence()
            .filterNot { cursor != null && it.idAsString == cursor }
            .take(limit + 1)
            .map { message ->
                AdminStreamPendingEntry(
                    id = message.idAsString,
                    consumer = message.consumerName,
                    idleMs = message.elapsedTimeSinceLastDelivery.toMillis(),
                    deliveryCount = message.totalDeliveryCount,
                    retryCount = (message.totalDeliveryCount - 1).coerceAtLeast(0L),
                )
            }
            .toList()
        AdminCursorPage(
            items = messages.take(limit),
            nextCursor = messages.take(limit).lastOrNull()?.id?.takeIf { messages.size > limit },
            hasMore = messages.size > limit,
            limit = limit,
        )
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

    private fun Map<String, Any>.longValue(key: String): Long? =
        when (val value = this[key]) {
            is Number -> value.toLong()
            is ByteArray -> value.toString(Charsets.UTF_8).toLongOrNull()
            else -> value?.toString()?.toLongOrNull()
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
        val ACTIVE_STREAM_KEY =
            Regex("[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.v[1-9][0-9]*")
        const val MAX_CONCURRENCY = 32
        const val PENDING_SUMMARY_SAMPLE_LIMIT = 100L
        val ACKNOWLEDGE_AND_DELETE = DefaultRedisScript(
            """
            redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            redis.call('XDEL', KEYS[1], ARGV[2])
            return 1
            """.trimIndent(),
            Boolean::class.java,
        )
    }
}
