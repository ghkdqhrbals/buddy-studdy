package com.buddystudy.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.TypeReference

class RedisStreamRuntimeHintsTest {
    @Test
    fun `registers redis stream heartbeat jackson models for native serialization`() {
        val hints = RuntimeHints()

        HibernateLoggerRuntimeHints().registerHints(hints, javaClass.classLoader)

        val registeredTypes = hints.reflection().typeHints().toList().map { it.type }.toSet()
        assertThat(registeredTypes).contains(
            TypeReference.of("com.redisstream.consumer.HeartbeatRequest"),
            TypeReference.of("com.redisstream.consumer.HeartbeatResponse"),
            TypeReference.of("com.redisstream.consumer.RuntimeConsumerCapacity"),
            TypeReference.of("com.redisstream.consumer.CoordinatorShard"),
            TypeReference.of("com.redisstream.consumer.RevokingShardReport"),
            TypeReference.of("com.redisstream.consumer.ShardConsumptionProgress"),
        )
    }
}
