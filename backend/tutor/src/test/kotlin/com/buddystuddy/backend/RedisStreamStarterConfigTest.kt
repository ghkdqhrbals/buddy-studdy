package com.buddystuddy.backend

import com.buddystuddy.backend.config.PropertiesConfig
import com.buddystuddy.backend.study.adapter.outbound.stream.RedisStreamStarterConfig
import com.redisstream.producer.ProducerRoutingProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RedisStreamStarterConfigTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java, RedisStreamStarterConfig::class.java)
        .withPropertyValues(
            "buddystuddy.crypto.master-key=test-master-key",
            "buddystuddy.auth.jwt-secret=test-jwt-secret",
            "buddystuddy.streams.push-prefix=bs-test-push",
            "buddystuddy.streams.max-len=1234",
        )

    @Test
    fun `streams enabled requires coordinator managed consumer`() {
        contextRunner
            .withPropertyValues("buddystuddy.streams.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("coordinatorManagedConsumer")
            }
    }

    @Test
    fun `streams require coordinator managed consumer by default`() {
        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasMessageContaining("coordinatorManagedConsumer")
        }
    }

    @Test
    fun `producer routing bean is not created when streams are disabled`() {
        contextRunner
            .withPropertyValues("buddystuddy.streams.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(ProducerRoutingProperties::class.java)
            }
    }
}
