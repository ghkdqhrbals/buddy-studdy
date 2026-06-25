package com.buddystuddy.backend.admin.analytics.adapter.outbound.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdminAnalyticsJdbcUrlTest {
    @Test
    fun `derives aggregation database url from primary postgres url`() {
        val result = AdminAnalyticsJdbcUrl.derive(
            primaryUrl = "jdbc:postgresql://buddystuddy-db:5432/buddystuddy",
            analyticsDatabaseName = "buddystuddy_aggregation",
        )

        assertThat(result).isEqualTo("jdbc:postgresql://buddystuddy-db:5432/buddystuddy_aggregation")
    }

    @Test
    fun `keeps postgres jdbc query parameters when deriving aggregation url`() {
        val result = AdminAnalyticsJdbcUrl.derive(
            primaryUrl = "jdbc:postgresql://api.ghkdqhrbals.org:5432/buddystuddy?sslmode=require",
            analyticsDatabaseName = "buddystuddy_aggregation",
        )

        assertThat(result).isEqualTo(
            "jdbc:postgresql://api.ghkdqhrbals.org:5432/buddystuddy_aggregation?sslmode=require",
        )
    }

    @Test
    fun `does not derive for non postgres test datasource`() {
        val result = AdminAnalyticsJdbcUrl.derive(
            primaryUrl = "jdbc:h2:mem:buddystuddy",
            analyticsDatabaseName = "buddystuddy_aggregation",
        )

        assertThat(result).isBlank()
    }

    @Test
    fun `does not derive for transient postgres test database`() {
        val result = AdminAnalyticsJdbcUrl.derive(
            primaryUrl = "jdbc:postgresql://localhost:54322/test",
            analyticsDatabaseName = "buddystuddy_aggregation",
        )

        assertThat(result).isBlank()
    }
}
