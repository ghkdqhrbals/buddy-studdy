package com.buddystuddy.backend.stream

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.redisstream.consumer.CoordinatorClient
import com.redisstream.producer.ProducerRoutingProperties
import com.redisstream.producer.RedisStreamPublisher
import com.redisstream.producer.RedisStreamXAddConfiguration
import com.redisstream.producer.StreamProducer
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory

@Configuration
class RedisStreamStarterConfig {
    @Bean
    @ConditionalOnProperty(prefix = "buddystuddy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun producerRoutingProperties(properties: BuddyStuddyProperties): ProducerRoutingProperties =
        ProducerRoutingProperties().apply {
            streamPrefix = properties.streams.pushPrefix
            consumerGroupName = "bs-push-workers"
            publishMaxAttempts = 2
            xadd.maxLen = properties.streams.maxLen
            xadd.approximateTrimming = true
        }

    @Bean("pushStreamPublisher")
    @ConditionalOnBean(CoordinatorClient::class, RedisConnectionFactory::class)
    @ConditionalOnProperty(prefix = "buddystuddy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun pushStreamPublisher(
        properties: BuddyStuddyProperties,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        streamPublisher(properties.streams.pushPrefix, "bs-push-workers", properties, client, redisConnectionFactory)

    @Bean("viewStreamPublisher")
    @ConditionalOnBean(CoordinatorClient::class, RedisConnectionFactory::class)
    @ConditionalOnProperty(prefix = "buddystuddy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun viewStreamPublisher(
        properties: BuddyStuddyProperties,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        streamPublisher(properties.streams.viewPrefix, "bs-view-workers", properties, client, redisConnectionFactory)

    @Bean("actionStreamPublisher")
    @ConditionalOnBean(CoordinatorClient::class, RedisConnectionFactory::class)
    @ConditionalOnProperty(prefix = "buddystuddy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun actionStreamPublisher(
        properties: BuddyStuddyProperties,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        streamPublisher(properties.streams.actionPrefix, "bs-question-action-workers", properties, client, redisConnectionFactory)

    private fun streamPublisher(
        streamPrefix: String,
        groupName: String,
        properties: BuddyStuddyProperties,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        StreamProducer(
            streamPrefix = streamPrefix,
            consumerGroupName = groupName,
            client = client,
            redisConnectionFactory = redisConnectionFactory,
            publishMaxAttempts = 2,
            xadd = RedisStreamXAddConfiguration(properties.streams.maxLen, true),
        )
}
