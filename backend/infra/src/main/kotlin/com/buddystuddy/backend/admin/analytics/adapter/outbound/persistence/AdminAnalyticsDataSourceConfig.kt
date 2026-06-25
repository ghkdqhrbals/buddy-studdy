package com.buddystuddy.backend.admin.analytics.adapter.outbound.persistence

import com.buddystuddy.backend.config.BuddyStuddyProperties
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
        @Qualifier("dataSource") primaryDataSource: DataSource,
    ): NamedParameterJdbcTemplate {
        val datasource = properties.analytics.datasource
        if (datasource.url.isBlank()) {
            return NamedParameterJdbcTemplate(primaryDataSource)
        }
        val builder = DataSourceBuilder.create()
            .url(datasource.url)
            .username(datasource.username)
            .password(datasource.password)
        if (datasource.driverClassName.isNotBlank()) {
            builder.driverClassName(datasource.driverClassName)
        }
        return NamedParameterJdbcTemplate(builder.build())
    }
}
