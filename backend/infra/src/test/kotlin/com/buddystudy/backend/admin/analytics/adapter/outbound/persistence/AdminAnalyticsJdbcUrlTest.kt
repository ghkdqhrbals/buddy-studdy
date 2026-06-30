package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdminAnalyticsJdbcUrlTest {
    @Test
    fun `derives aggregation database url from primary postgres url`() {
        val result = AdminAnalyticsJdbcUrl.derive(
            primaryUrl = "jdbc:postgresql://buddystudy-db:5432/buddystudy",
            analyticsDatabaseName = "buddystudy_aggregation",
        )

        assertThat(result).isEqualTo("jdbc:postgresql://buddystudy-db:5432/buddystudy_aggregation")
    }

    @Test
    fun `keeps postgres jdbc query parameters when deriving aggregation url`() {
        val result = AdminAnalyticsJdbcUrl.derive(
            primaryUrl = "jdbc:postgresql://api.ghkdqhrbals.org:5432/buddystudy?sslmode=require",
            analyticsDatabaseName = "buddystudy_aggregation",
        )

        assertThat(result).isEqualTo(
            "jdbc:postgresql://api.ghkdqhrbals.org:5432/buddystudy_aggregation?sslmode=require",
        )
    }

    @Test
    fun `does not derive for non postgres test datasource`() {
        val result = AdminAnalyticsJdbcUrl.derive(
            primaryUrl = "jdbc:h2:mem:buddystudy",
            analyticsDatabaseName = "buddystudy_aggregation",
        )

        assertThat(result).isBlank()
    }

    @Test
    fun `does not derive for transient postgres test database`() {
        val result = AdminAnalyticsJdbcUrl.derive(
            primaryUrl = "jdbc:postgresql://localhost:54322/test",
            analyticsDatabaseName = "buddystudy_aggregation",
        )

        assertThat(result).isBlank()
    }
}
