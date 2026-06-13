package com.buddystuddy.backend.study.adapter.outbound.stream

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.redisstream.consumer.CoordinatorClient
import com.redisstream.producer.RedisStreamPublisher
import com.redisstream.producer.RedisStreamXAddConfiguration
import com.redisstream.producer.StreamProducer
import org.springframework.beans.factory.annotation.Value
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
        @Value("\${PUSH_CONSUMER_GROUP_NAME:bs-push-workers}")
        consumerGroupName: String,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        streamPublisher(
            StreamPublisherDefinition(properties.streams.pushPrefix, consumerGroupName),
            properties,
            client,
            redisConnectionFactory,
        )

    @Bean("viewStreamPublisher")
    @ConditionalOnProperty(prefix = "buddystuddy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun viewStreamPublisher(
        properties: BuddyStuddyProperties,
        @Value("\${VIEW_CONSUMER_GROUP_NAME:bs-view-workers}")
        consumerGroupName: String,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        streamPublisher(
            StreamPublisherDefinition(properties.streams.viewPrefix, consumerGroupName),
            properties,
            client,
            redisConnectionFactory,
        )

    @Bean("actionStreamPublisher")
    @ConditionalOnProperty(prefix = "buddystuddy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun actionStreamPublisher(
        properties: BuddyStuddyProperties,
        @Value("\${ACTION_CONSUMER_GROUP_NAME:bs-question-action-workers}")
        consumerGroupName: String,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        streamPublisher(
            StreamPublisherDefinition(properties.streams.actionPrefix, consumerGroupName),
            properties,
            client,
            redisConnectionFactory,
        )

    @Bean("questionSearchRedisStreamPublisher")
    @ConditionalOnProperty(prefix = "buddystuddy.streams", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun questionSearchRedisStreamPublisher(
        properties: BuddyStuddyProperties,
        @Value("\${QUESTION_SEARCH_CONSUMER_GROUP_NAME:question-reader}")
        consumerGroupName: String,
        client: CoordinatorClient,
        redisConnectionFactory: RedisConnectionFactory,
    ): RedisStreamPublisher =
        streamPublisher(
            StreamPublisherDefinition(properties.streams.questionSearchPrefix, consumerGroupName),
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
            consumerGroupName = definition.consumerGroupName,
            client = client,
            redisConnectionFactory = redisConnectionFactory,
            publishMaxAttempts = 2,
            xadd = RedisStreamXAddConfiguration(properties.streams.maxLen, true),
        )

    private data class StreamPublisherDefinition(
        val streamPrefix: String,
        val consumerGroupName: String,
    )
}
