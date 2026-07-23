package com.buddystudy.backend.config

import io.r2dbc.spi.ConnectionFactoryOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class R2dbcConnectionDetailsConfigTest {
    @Test
    fun `builds connection options from Spring R2DBC settings`() {
        val details =
            EnvironmentR2dbcConnectionDetails(
                MockEnvironment()
                    .withProperty("spring.r2dbc.url", "r2dbc:postgresql://db:5432/buddystudy")
                    .withProperty("spring.r2dbc.username", "app-user")
                    .withProperty("spring.r2dbc.password", "app-password"),
            )

        val options = details.connectionFactoryOptions

        assertThat(options.getValue(ConnectionFactoryOptions.HOST)).isEqualTo("db")
        assertThat(options.getValue(ConnectionFactoryOptions.DATABASE)).isEqualTo("buddystudy")
        assertThat(options.getValue(ConnectionFactoryOptions.USER)).isEqualTo("app-user")
        assertThat(options.getValue(ConnectionFactoryOptions.PASSWORD).toString()).isEqualTo("app-password")
    }

    @Test
    fun `keeps credentials embedded in the R2DBC URL`() {
        val details =
            EnvironmentR2dbcConnectionDetails(
                MockEnvironment()
                    .withProperty(
                        "spring.r2dbc.url",
                        "r2dbc:postgresql://url-user:url-password@db:5432/buddystudy",
                    )
                    .withProperty("spring.r2dbc.username", "property-user")
                    .withProperty("spring.r2dbc.password", "property-password"),
            )

        val options = details.connectionFactoryOptions

        assertThat(options.getValue(ConnectionFactoryOptions.USER)).isEqualTo("url-user")
        assertThat(options.getValue(ConnectionFactoryOptions.PASSWORD).toString()).isEqualTo("url-password")
    }
}
