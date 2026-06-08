package com.buddystuddy.backend.stream

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.redisstream.producer.ProducerRoutingProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RedisStreamStarterConfig {
    @Bean
    fun producerRoutingProperties(properties: BuddyStuddyProperties): ProducerRoutingProperties =
        ProducerRoutingProperties().apply {
            streamPrefix = properties.streams.pushPrefix
            consumerGroupName = "bs-push-workers"
            publishMaxAttempts = 2
            xadd.maxLen = properties.streams.maxLen
            xadd.approximateTrimming = true
        }
}
