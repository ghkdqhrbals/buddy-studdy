package com.buddystudy.backend.common.adapter.outbound.redis

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisStreamRetiredTopologyCleaner(
    private val redis: StringRedisTemplate,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun clean() {
        runCatching {
            val existing = RETIRED_STREAM_KEYS.filter { redis.hasKey(it) == true }
            if (existing.isEmpty()) return
            val deleted = redis.delete(existing)
            logger.info(
                "redis_stream_retired_topology_removed deleted={} streams={}",
                deleted,
                existing,
            )
            if (deleted != existing.size.toLong()) {
                logger.warn(
                    "redis_stream_retired_topology_partially_removed expected={} deleted={} streams={}",
                    existing.size,
                    deleted,
                    existing,
                )
            }
        }.onFailure { error ->
            logger.error(
                "redis_stream_retired_topology_cleanup_failed streams={}",
                RETIRED_STREAM_KEYS,
                error,
            )
        }
    }

    private companion object {
        val RETIRED_STREAM_KEYS = listOf(
            "buddystudy-events-v1",
            "buddystudy-question-generation-v1",
            "buddystudy-question-generated-v1",
            "buddystudy-content-translation-v1",
            "buddystudy-push-v1",
            "buddystudy-native-push-test-20260728",
        )
    }
}
