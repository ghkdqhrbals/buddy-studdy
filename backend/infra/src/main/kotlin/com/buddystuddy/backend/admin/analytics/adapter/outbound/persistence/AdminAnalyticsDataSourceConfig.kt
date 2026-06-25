package com.buddystuddy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystuddy.backend.config.BuddyStuddyProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import javax.sql.DataSource

@Configuration
class AdminAnalyticsDataSourceConfig {
    @Bean("adminAnalyticsJdbcTemplate")
    fun adminAnalyticsJdbcTemplate(
        properties: BuddyStuddyProperties,
        @Value("\${spring.datasource.url:}") primaryDataSourceUrl: String,
        @Qualifier("dataSource") primaryDataSource: DataSource,
    ): NamedParameterJdbcTemplate {
        val datasource = properties.analytics.datasource
        val analyticsUrl = datasource.url.ifBlank {
            AdminAnalyticsJdbcUrl.derive(primaryDataSourceUrl, datasource.databaseName)
        }
        if (analyticsUrl.isBlank()) {
            return NamedParameterJdbcTemplate(primaryDataSource)
        }
        val builder = DataSourceBuilder.create()
            .url(analyticsUrl)
            .username(datasource.username)
            .password(datasource.password)
        if (datasource.driverClassName.isNotBlank()) {
            builder.driverClassName(datasource.driverClassName)
        }
        return NamedParameterJdbcTemplate(builder.build())
    }
}

internal object AdminAnalyticsJdbcUrl {
    private val databasePath = Regex("""/([^/?]+)(\?.*)?$""")

    fun derive(primaryUrl: String, analyticsDatabaseName: String): String {
        if (!primaryUrl.startsWith("jdbc:postgresql:") || analyticsDatabaseName.isBlank()) {
            return ""
        }
        val result = databasePath.find(primaryUrl) ?: return ""
        val primaryDatabaseName = result.groupValues.getOrNull(1).orEmpty()
        if (primaryDatabaseName != "buddystuddy") {
            return ""
        }
        val query = result.groupValues.getOrNull(2).orEmpty()
        return primaryUrl.replaceRange(result.range, "/$analyticsDatabaseName$query")
    }
}
