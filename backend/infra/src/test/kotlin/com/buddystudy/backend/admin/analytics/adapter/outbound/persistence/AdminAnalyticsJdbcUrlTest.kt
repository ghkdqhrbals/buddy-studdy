package com.buddystudy.backend.admin.analytics.adapter.outbound.persistence

import io.r2dbc.spi.ConnectionFactoryOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdminAnalyticsR2dbcUrlTest {
    @Test
    fun `normalizes explicitly configured mysql jdbc url`() {
        val result = AdminAnalyticsR2dbcUrl.normalizeConfigured(
            "jdbc:mysql://buddystudy-db:3306/buddystudy_aggregation?serverTimezone=UTC",
        )

        assertThat(result).isEqualTo(
            "r2dbc:mysql://buddystudy-db:3306/buddystudy_aggregation?serverZoneId=UTC",
        )
    }

    @Test
    fun `keeps explicitly configured mysql r2dbc url`() {
        val result = AdminAnalyticsR2dbcUrl.normalizeConfigured(
            "r2dbc:mysql://api.ghkdqhrbals.org:3306/buddystudy_aggregation?sslMode=VERIFY_IDENTITY",
        )

        assertThat(result).isEqualTo(
            "r2dbc:mysql://api.ghkdqhrbals.org:3306/buddystudy_aggregation?sslMode=VERIFY_IDENTITY",
        )
    }

    @Test
    fun `uses primary datasource when no analytics url is configured`() {
        val result = AdminAnalyticsR2dbcUrl.normalizeConfigured("")

        assertThat(result).isBlank()
    }

    @Test
    fun `rejects unsupported analytics datasource driver`() {
        val result = AdminAnalyticsR2dbcUrl.normalizeConfigured(
            "jdbc:postgresql://buddystudy-db:5432/buddystudy_aggregation",
        )

        assertThat(result).isBlank()
    }

    @Test
    fun `uses primary credentials when derived analytics url has none`() {
        val result = AdminAnalyticsR2dbcUrl.options(
            analyticsUrl = "r2dbc:mysql://localhost:3306/buddystudy_aggregation",
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
            analyticsUrl = "r2dbc:mysql://analytics:analytics-password@localhost:3306/buddystudy_aggregation",
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
            analyticsUrl = "r2dbc:mysql://url-user:url-password@localhost:3306/buddystudy_aggregation",
            configuredUsername = "configured-user",
            configuredPassword = "configured-password",
            primaryUsername = "buddystudy",
            primaryPassword = "local-password",
        )

        assertThat(result.getValue(ConnectionFactoryOptions.USER)).isEqualTo("configured-user")
        assertThat(result.getValue(ConnectionFactoryOptions.PASSWORD).toString()).isEqualTo("configured-password")
    }
}
