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
        primaryConnectionFactory: ConnectionFactory,
    ): DatabaseClient {
        val datasource = properties.analytics.datasource
        val configured = datasource.url.replace("jdbc:postgresql:", "r2dbc:postgresql:")
        val analyticsUrl = configured.ifBlank { AdminAnalyticsR2dbcUrl.derive(primaryUrl, datasource.databaseName) }
        if (analyticsUrl.isBlank()) return DatabaseClient.create(primaryConnectionFactory)
        val builder = ConnectionFactoryOptions.parse(analyticsUrl).mutate()
        if (datasource.username.isNotBlank()) builder.option(ConnectionFactoryOptions.USER, datasource.username)
        if (datasource.password.isNotBlank()) builder.option(ConnectionFactoryOptions.PASSWORD, datasource.password)
        return DatabaseClient.create(ConnectionFactories.get(builder.build()))
    }
}

internal object AdminAnalyticsR2dbcUrl {
    private val databasePath = Regex("""/([^/?]+)(\?.*)?$""")

    fun derive(primaryUrl: String, analyticsDatabaseName: String): String {
        if (!primaryUrl.startsWith("r2dbc:postgresql:") || analyticsDatabaseName.isBlank()) return ""
        val result = databasePath.find(primaryUrl) ?: return ""
        if (result.groupValues.getOrNull(1).orEmpty() != "buddystudy") return ""
        val query = result.groupValues.getOrNull(2).orEmpty()
        return primaryUrl.replaceRange(result.range, "/$analyticsDatabaseName$query")
    }
}
