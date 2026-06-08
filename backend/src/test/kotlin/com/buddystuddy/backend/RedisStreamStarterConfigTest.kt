package com.buddystuddy.backend

import com.buddystuddy.backend.config.PropertiesConfig
import com.buddystuddy.backend.stream.RedisStreamStarterConfig
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
    fun `producer routing bean is created when streams are enabled`() {
        contextRunner
            .withPropertyValues("buddystuddy.streams.enabled=true")
            .run { context ->
                assertThat(context).hasSingleBean(ProducerRoutingProperties::class.java)
                val properties = context.getBean(ProducerRoutingProperties::class.java)
                assertThat(properties.streamPrefix).isEqualTo("bs-test-push")
                assertThat(properties.consumerGroupName).isEqualTo("bs-push-workers")
                assertThat(properties.publishMaxAttempts).isEqualTo(2)
                assertThat(properties.xadd.maxLen).isEqualTo(1234)
                assertThat(properties.xadd.approximateTrimming).isTrue()
            }
    }

    @Test
    fun `producer routing bean is created by default`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(ProducerRoutingProperties::class.java)
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
