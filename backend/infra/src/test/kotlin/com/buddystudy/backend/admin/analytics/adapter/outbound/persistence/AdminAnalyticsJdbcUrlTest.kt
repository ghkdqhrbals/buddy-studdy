package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import kotlinx.coroutines.runBlocking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdminAnalyticsR2dbcUrlTest {
    @Test
    fun `derives aggregation database url from primary postgres url`(): Unit = runBlocking {
        val result = AdminAnalyticsR2dbcUrl.derive(
            primaryUrl = "r2dbc:postgresql://buddystudy-db:5432/buddystudy",
            analyticsDatabaseName = "buddystudy_aggregation",
        )

        assertThat(result).isEqualTo("r2dbc:postgresql://buddystudy-db:5432/buddystudy_aggregation")
    }

    @Test
    fun `keeps postgres r2dbc query parameters when deriving aggregation url`(): Unit = runBlocking {
        val result = AdminAnalyticsR2dbcUrl.derive(
            primaryUrl = "r2dbc:postgresql://api.ghkdqhrbals.org:5432/buddystudy?sslmode=require",
            analyticsDatabaseName = "buddystudy_aggregation",
        )

        assertThat(result).isEqualTo(
            "r2dbc:postgresql://api.ghkdqhrbals.org:5432/buddystudy_aggregation?sslmode=require",
        )
    }

    @Test
    fun `does not derive for non postgres test datasource`(): Unit = runBlocking {
        val result = AdminAnalyticsR2dbcUrl.derive(
            primaryUrl = "r2dbc:h2:mem:///buddystudy",
            analyticsDatabaseName = "buddystudy_aggregation",
        )

        assertThat(result).isBlank()
    }

    @Test
    fun `does not derive for transient postgres test database`(): Unit = runBlocking {
        val result = AdminAnalyticsR2dbcUrl.derive(
            primaryUrl = "r2dbc:postgresql://localhost:54322/test",
            analyticsDatabaseName = "buddystudy_aggregation",
        )

        assertThat(result).isBlank()
    }
}
