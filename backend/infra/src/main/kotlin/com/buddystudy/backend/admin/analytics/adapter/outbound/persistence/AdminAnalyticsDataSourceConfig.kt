package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystudy.backend.config.BuddyStudyProperties
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient

@Configuration
class AdminAnalyticsDataSourceConfig {
    @Bean("adminAnalyticsDatabaseClient")
    fun adminAnalyticsDatabaseClient(
        properties: BuddyStudyProperties,
        @Value("\${spring.r2dbc.url:}") primaryUrl: String,
        @Value("\${spring.r2dbc.username:}") primaryUsername: String,
        @Value("\${spring.r2dbc.password:}") primaryPassword: String,
        primaryConnectionFactory: ConnectionFactory,
    ): AdminAnalyticsDatabaseClient {
        val datasource = properties.analytics.datasource
        val configured = datasource.url.replace("jdbc:postgresql:", "r2dbc:postgresql:")
        val analyticsUrl = configured.ifBlank { AdminAnalyticsR2dbcUrl.derive(primaryUrl, datasource.databaseName) }
        if (analyticsUrl.isBlank()) {
            return AdminAnalyticsDatabaseClient(DatabaseClient.create(primaryConnectionFactory))
        }
        val options = AdminAnalyticsR2dbcUrl.options(
            analyticsUrl = analyticsUrl,
            configuredUsername = datasource.username,
            configuredPassword = datasource.password,
            primaryUsername = primaryUsername,
            primaryPassword = primaryPassword,
        )
        return AdminAnalyticsDatabaseClient(DatabaseClient.create(ConnectionFactories.get(options)))
    }
}

class AdminAnalyticsDatabaseClient(val client: DatabaseClient)

internal object AdminAnalyticsR2dbcUrl {
    private val databasePath = Regex("""/([^/?]+)(\?.*)?$""")

    fun derive(primaryUrl: String, analyticsDatabaseName: String): String {
        if (!primaryUrl.startsWith("r2dbc:postgresql:") || analyticsDatabaseName.isBlank()) return ""
        val result = databasePath.find(primaryUrl) ?: return ""
        if (result.groupValues.getOrNull(1).orEmpty() != "buddystudy") return ""
        val query = result.groupValues.getOrNull(2).orEmpty()
        return primaryUrl.replaceRange(result.range, "/$analyticsDatabaseName$query")
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
