package com.buddystudy.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

class CoordinatorSecretMappingTest {
    @Test
    fun `coordinator password is mapped from deployment secrets`() {
        val propertySources = YamlPropertySourceLoader()
            .load("application.yml", ClassPathResource("application.yml"))
        val properties = propertySources
            .firstNotNullOf { it.getProperty("redis-stream-coordinator.password") as? String }

        assertThat(properties).contains("REACTION_STREAM_COORDINATOR_PASSWORD")
        assertThat(properties).contains("REDIS_STREAM_COORDINATOR_PASSWORD")
    }
}
