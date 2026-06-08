package com.buddystuddy.backend.community.adapter.outbound.redis

import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionAggregateCommandPort
import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionAggregateQueryPort
import com.buddystuddy.backend.community.domain.PublicQuestionSnapshot
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component("publicQuestionAggregateRedisAdapter")
class PublicQuestionAggregateRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: BuddyStuddyProperties,
) : PublicQuestionAggregateQueryPort, PublicQuestionAggregateCommandPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun findByQuestionId(questionId: Long): PublicQuestionSnapshot? =
        runCatching {
            redisTemplate.opsForValue().get(key(questionId))?.let {
                objectMapper.readValue(it, PublicQuestionSnapshot::class.java)
            }
        }.onFailure {
            logger.warn("public_question_cache_read_failed questionId={} error={}", questionId, it.message)
        }.getOrNull()

    override fun save(questionId: Long, snapshot: PublicQuestionSnapshot) {
        runCatching {
            redisTemplate.opsForValue().set(
                key(questionId),
                objectMapper.writeValueAsString(snapshot),
                Duration.ofSeconds(properties.cache.publicQuestionTtlSeconds),
            )
        }.onFailure {
            logger.warn("public_question_cache_write_failed questionId={} error={}", questionId, it.message)
        }
    }

    override fun evict(questionId: Long) {
        runCatching {
            redisTemplate.delete(key(questionId))
        }.onFailure {
            logger.warn("public_question_cache_evict_failed questionId={} error={}", questionId, it.message)
        }
    }

    private fun key(questionId: Long) = "buddystuddy:public-question:v1:$questionId"
}
