package com.buddystudy.backend.common.adapter.outbound.redis

import com.buddystudy.backend.config.BuddyStudyProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisStreamLegacyTopologyCleaner(
    private val redis: StringRedisTemplate,
    properties: BuddyStudyProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val domainStreamKey = properties.streams.key

    @EventListener(ApplicationReadyEvent::class)
    fun clean() {
        LEGACY_DOMAIN_GROUPS.forEach { removeEmptyLegacyGroup(domainStreamKey, it) }
        removeEmptyTestStream(LEGACY_NATIVE_PUSH_TEST_STREAM)
    }

    private fun removeEmptyLegacyGroup(streamKey: String, groupName: String) {
        runCatching {
            val streams = redis.opsForStream<String, String>()
            val group = streams.groups(streamKey).firstOrNull { it.groupName() == groupName } ?: return
            if (group.pendingCount() > 0) {
                logger.warn(
                    "redis_stream_legacy_group_cleanup_skipped stream={} group={} pending={}",
                    streamKey,
                    groupName,
                    group.pendingCount(),
                )
                return
            }
            if (streams.destroyGroup(streamKey, groupName) == true) {
                logger.info("redis_stream_legacy_group_removed stream={} group={}", streamKey, groupName)
            }
        }.onFailure { error ->
            logger.warn(
                "redis_stream_legacy_group_cleanup_failed stream={} group={} errorType={} error={}",
                streamKey,
                groupName,
                error.javaClass.name,
                error.message,
            )
        }
    }

    private fun removeEmptyTestStream(streamKey: String) {
        runCatching {
            if (redis.hasKey(streamKey) != true) return
            if (redis.opsForStream<String, String>().size(streamKey) != 0L) return
            val groups = redis.opsForStream<String, String>().groups(streamKey)
            if (groups.any { it.pendingCount() > 0 }) {
                logger.warn("redis_stream_test_cleanup_skipped stream={} reason=pending_messages", streamKey)
                return
            }
            if (redis.delete(streamKey)) {
                logger.info("redis_stream_unused_test_removed stream={}", streamKey)
            }
        }.onFailure { error ->
            logger.warn(
                "redis_stream_test_cleanup_failed stream={} errorType={} error={}",
                streamKey,
                error.javaClass.name,
                error.message,
            )
        }
    }

    private companion object {
        val LEGACY_DOMAIN_GROUPS = listOf(
            "bs-backend-push",
            "bs-backend-question-search",
            "bs-backend-question-translation",
        )
        const val LEGACY_NATIVE_PUSH_TEST_STREAM = "buddystudy-native-push-test-20260728"
    }
}
