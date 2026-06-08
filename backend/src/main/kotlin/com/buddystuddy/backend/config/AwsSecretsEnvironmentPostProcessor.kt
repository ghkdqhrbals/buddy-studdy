package com.buddystuddy.backend.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest

class AwsSecretsEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val secretId = environment.getProperty("AWS_SECRET_ID")?.takeIf { it.isNotBlank() } ?: return
        val region = environment.getProperty("AWS_REGION")?.takeIf { it.isNotBlank() } ?: "ap-northeast-2"
        val values = runCatching {
            val client = SecretsManagerClient.builder().region(Region.of(region)).build()
            val response = client.getSecretValue(GetSecretValueRequest.builder().secretId(secretId).build())
            jacksonObjectMapper().readValue<Map<String, Any?>>(response.secretString() ?: "{}")
        }.getOrElse { return }

        val mapped = buildMap<String, Any> {
            fun putIfPresent(property: String, vararg keys: String) {
                keys.firstNotNullOfOrNull { values[it]?.toString()?.takeIf(String::isNotBlank) }?.let { put(property, it) }
            }
            putIfPresent("spring.datasource.url", "springDatasourceUrl", "databaseUrl")
            putIfPresent("spring.datasource.username", "springDatasourceUsername", "databaseUsername")
            putIfPresent("spring.datasource.password", "springDatasourcePassword", "databasePassword")
            putIfPresent("spring.data.redis.host", "redisHost", "REDIS_HOST")
            putIfPresent("spring.data.redis.port", "redisPort", "REDIS_PORT")
            putIfPresent("spring.data.redis.password", "redisPassword", "REDIS_PASSWORD")
            putIfPresent("buddystuddy.streams.coordinator-password", "reactionStreamCoordinatorPassword", "REDIS_STREAM_COORDINATOR_PASSWORD")
            putIfPresent("buddystuddy.streams.coordinator-username", "reactionStreamCoordinatorUsername", "REDIS_STREAM_COORDINATOR_USERNAME")
            putIfPresent("redis-stream-coordinator.password", "reactionStreamCoordinatorPassword", "REDIS_STREAM_COORDINATOR_PASSWORD")
            putIfPresent("redis-stream-coordinator.username", "reactionStreamCoordinatorUsername", "REDIS_STREAM_COORDINATOR_USERNAME")
            putIfPresent("spring.mail.host", "smtpHost", "SMTP_HOST")
            putIfPresent("spring.mail.username", "smtpUsername", "SMTP_USERNAME")
            putIfPresent("spring.mail.password", "smtpPassword", "SMTP_PASSWORD")
            putIfPresent("buddystuddy.email.from", "smtpFrom", "SMTP_FROM")
        }
        if (mapped.isNotEmpty()) {
            environment.propertySources.addFirst(MapPropertySource("aws-secrets-manager-$secretId", mapped))
        }
    }
}
