package com.buddystudy.backend

import com.buddystudy.backend.config.PropertiesConfig
import com.buddystudy.backend.study.adapter.outbound.stream.RedisStreamStarterConfig
import com.redisstream.consumer.CoordinatorClient
import com.redisstream.consumer.HeartbeatRequest
import com.redisstream.consumer.HeartbeatResponse
import com.redisstream.consumer.ProducerRoutingResponse
import com.redisstream.consumer.ProducerRoutingShard
import com.redisstream.producer.RedisStreamPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import java.lang.reflect.Proxy

class RedisStreamStarterConfigTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java, RedisStreamStarterConfig::class.java)
        .withPropertyValues(
            "buddystudy.crypto.master-key=test-master-key",
            "buddystudy.auth.jwt-secret=test-jwt-secret",
            "buddystudy.streams.push-prefix=bs-test-push",
            "buddystudy.streams.view-prefix=bs-test-view",
            "buddystudy.streams.action-prefix=bs-test-action",
            "buddystudy.streams.max-len=1234",
        )

    @Test
    fun `stream publishers are created when streams are enabled`() {
        contextRunner
            .withPropertyValues("buddystudy.streams.enabled=true")
            .withBean(CoordinatorClient::class.java, { RoutingCoordinatorClient() })
            .withBean(RedisConnectionFactory::class.java, { redisConnectionFactoryProxy() })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasBean("pushStreamPublisher")
                assertThat(context).hasBean("viewStreamPublisher")
                assertThat(context).getBean("pushStreamPublisher").isInstanceOf(RedisStreamPublisher::class.java)
                assertThat(context).getBean("viewStreamPublisher").isInstanceOf(RedisStreamPublisher::class.java)
            }
    }

    @Test
    fun `stream publishers are enabled by default`() {
        contextRunner
            .withBean(CoordinatorClient::class.java, { RoutingCoordinatorClient() })
            .withBean(RedisConnectionFactory::class.java, { redisConnectionFactoryProxy() })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasBean("pushStreamPublisher")
                assertThat(context).hasBean("viewStreamPublisher")
            }
    }

    @Test
    fun `stream publishers are not created when streams are disabled`() {
        contextRunner
            .withPropertyValues("buddystudy.streams.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("pushStreamPublisher")
                assertThat(context).doesNotHaveBean("viewStreamPublisher")
            }
    }

    private class RoutingCoordinatorClient : CoordinatorClient {
        override fun heartbeat(
            streamPrefix: String,
            consumerGroup: String,
            memberId: String,
            request: HeartbeatRequest,
        ): HeartbeatResponse =
            error("heartbeat is not used by publisher configuration tests")

        override fun producerRouting(streamPrefix: String): ProducerRoutingResponse =
            ProducerRoutingResponse(
                streamPrefix = streamPrefix,
                metadataVersion = 1,
                shardCount = 1,
                streamKeyPattern = "$streamPrefix:{shardIndex}",
                shards = listOf(
                    ProducerRoutingShard(
                        shardIndex = 0,
                        streamKey = "$streamPrefix:0",
                        redisSlot = 0,
                    ),
                ),
            )
    }

    private fun redisConnectionFactoryProxy(): RedisConnectionFactory =
        Proxy.newProxyInstance(
            RedisConnectionFactory::class.java.classLoader,
            arrayOf(RedisConnectionFactory::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0f
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> 0.toChar()
                Void.TYPE -> Unit
                else -> null
            }
        } as RedisConnectionFactory
}
