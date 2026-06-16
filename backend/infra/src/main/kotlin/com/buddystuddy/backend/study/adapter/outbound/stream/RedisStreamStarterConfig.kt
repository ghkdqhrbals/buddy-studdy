package com.buddystuddy.backend.study.adapter.outbound.stream

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.redisstream.consumer.CoordinatorClient
import com.redisstream.producer.RedisStreamPublisher
import com.redisstream.producer.RedisStreamXAddConfiguration
import com.redisstream.producer.StreamProducer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory

@Configuration
class RedisStreamStarterConfig {
    @Bean("pushStreamPublisher")
    @ConditionalOnProperty(prefix = "buddystuddy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun pushStreamPublisher(
        properties: BuddyStuddyProperties,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        streamPublisher(
            StreamPublisherDefinition(properties.streams.pushPrefix),
            properties,
            client,
            redisConnectionFactory,
        )

    @Bean("viewStreamPublisher")
    @ConditionalOnProperty(prefix = "buddystuddy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun viewStreamPublisher(
        properties: BuddyStuddyProperties,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        streamPublisher(
            StreamPublisherDefinition(properties.streams.viewPrefix),
            properties,
            client,
            redisConnectionFactory,
        )

    @Bean("notificationStreamPublisher")
    @ConditionalOnProperty(prefix = "buddystuddy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun notificationStreamPublisher(
        properties: BuddyStuddyProperties,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        streamPublisher(
            StreamPublisherDefinition(properties.streams.notificationPrefix),
            properties,
            client,
            redisConnectionFactory,
        )

    private fun streamPublisher(
        definition: StreamPublisherDefinition,
        properties: BuddyStuddyProperties,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        StreamProducer(
            streamPrefix = definition.streamPrefix,
            client = client,
            redisConnectionFactory = redisConnectionFactory,
            publishMaxAttempts = 2,
            xadd = RedisStreamXAddConfiguration(properties.streams.maxLen, true),
        )

    private data class StreamPublisherDefinition(
        val streamPrefix: String,
    )
}
