package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import io.r2dbc.spi.ConnectionFactoryOptions
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

    @Test
    fun `uses primary credentials when derived analytics url has none`() {
        val result = AdminAnalyticsR2dbcUrl.options(
            analyticsUrl = "r2dbc:postgresql://localhost:5432/buddystudy_aggregation",
            configuredUsername = "",
            configuredPassword = "",
            primaryUsername = "buddystudy",
            primaryPassword = "local-password",
        )

        assertThat(result.getValue(ConnectionFactoryOptions.USER)).isEqualTo("buddystudy")
        assertThat(result.getValue(ConnectionFactoryOptions.PASSWORD).toString()).isEqualTo("local-password")
    }

    @Test
    fun `keeps credentials embedded in analytics url ahead of primary credentials`() {
        val result = AdminAnalyticsR2dbcUrl.options(
            analyticsUrl = "r2dbc:postgresql://analytics:analytics-password@localhost:5432/buddystudy_aggregation",
            configuredUsername = "",
            configuredPassword = "",
            primaryUsername = "buddystudy",
            primaryPassword = "local-password",
        )

        assertThat(result.getValue(ConnectionFactoryOptions.USER)).isEqualTo("analytics")
        assertThat(result.getValue(ConnectionFactoryOptions.PASSWORD).toString()).isEqualTo("analytics-password")
    }

    @Test
    fun `configured analytics credentials override url and primary credentials`() {
        val result = AdminAnalyticsR2dbcUrl.options(
            analyticsUrl = "r2dbc:postgresql://url-user:url-password@localhost:5432/buddystudy_aggregation",
            configuredUsername = "configured-user",
            configuredPassword = "configured-password",
            primaryUsername = "buddystudy",
            primaryPassword = "local-password",
        )

        assertThat(result.getValue(ConnectionFactoryOptions.USER)).isEqualTo("configured-user")
        assertThat(result.getValue(ConnectionFactoryOptions.PASSWORD).toString()).isEqualTo("configured-password")
    }
}
