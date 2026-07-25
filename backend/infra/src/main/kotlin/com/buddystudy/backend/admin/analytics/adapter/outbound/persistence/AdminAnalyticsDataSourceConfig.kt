package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystudy.backend.config.BuddyStudyProperties
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient

@Configuration
class AdminAnalyticsDataSourceConfig {
    @Bean("adminAnalyticsDatabaseClient")
    fun adminAnalyticsDatabaseClient(
        properties: BuddyStudyProperties,
        primaryConnectionFactory: ConnectionFactory,
    ): AdminAnalyticsDatabaseClient {
        val datasource = properties.analytics.datasource
        val analyticsUrl = AdminAnalyticsR2dbcUrl.normalizeConfigured(datasource.url)
        if (analyticsUrl.isBlank()) {
            if (datasource.url.isNotBlank()) {
                logger.warn(
                    "Ignoring unsupported admin analytics datasource URL; using the primary R2DBC connection instead",
                )
            }
            return AdminAnalyticsDatabaseClient(DatabaseClient.create(primaryConnectionFactory))
        }
        val options = AdminAnalyticsR2dbcUrl.options(
            analyticsUrl = analyticsUrl,
            configuredUsername = datasource.username,
            configuredPassword = datasource.password,
            primaryUsername = "",
            primaryPassword = "",
        )
        return AdminAnalyticsDatabaseClient(DatabaseClient.create(ConnectionFactories.get(options)))
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AdminAnalyticsDataSourceConfig::class.java)
    }
}

class AdminAnalyticsDatabaseClient(val client: DatabaseClient)

internal object AdminAnalyticsR2dbcUrl {
    fun normalizeConfigured(configuredUrl: String): String {
        val url = configuredUrl.trim()
        return when {
            url.startsWith("jdbc:mysql:") -> url.replaceFirst("jdbc:mysql:", "r2dbc:mysql:")
            url.startsWith("r2dbc:mysql:") -> url
            else -> ""
        }.replace("serverTimezone=", "serverZoneId=")
    }

    fun options(
        analyticsUrl: String,
        configuredUsername: String,
        configuredPassword: String,
        primaryUsername: String,
        primaryPassword: String,
    ): ConnectionFactoryOptions {
        val parsed = ConnectionFactoryOptions.parse(analyticsUrl)
        val builder = parsed.mutate()
        val username = configuredUsername.ifBlank {
            (parsed.getValue(ConnectionFactoryOptions.USER) as? String).orEmpty()
        }.ifBlank { primaryUsername }
        val password = configuredPassword.ifBlank {
            parsed.getValue(ConnectionFactoryOptions.PASSWORD)?.toString().orEmpty()
        }.ifBlank { primaryPassword }
        if (username.isNotBlank()) builder.option(ConnectionFactoryOptions.USER, username)
        if (password.isNotBlank()) builder.option(ConnectionFactoryOptions.PASSWORD, password)
        return builder.build()
    }
}
