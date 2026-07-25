package com.buddystudy.backend.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class FlywayDataSourceSettingsTest {
    @Test
    fun `uses effective R2DBC settings when Flyway and environment credentials are absent`() {
        val environment =
            MockEnvironment()
                .withProperty("spring.r2dbc.url", "r2dbc:mysql://localhost:3306/buddystudy")
                .withProperty("spring.r2dbc.username", "buddystudy")
                .withProperty("spring.r2dbc.password", "local-password")

        val settings = FlywayDataSourceSettings.from(environment)

        assertThat(settings.url).isEqualTo("jdbc:mysql://localhost:3306/buddystudy")
        assertThat(settings.username).isEqualTo("buddystudy")
        assertThat(settings.password).isEqualTo("local-password")
    }

    @Test
    fun `maps the R2DBC MySQL timezone option to JDBC`() {
        val environment =
            MockEnvironment()
                .withProperty(
                    "spring.r2dbc.url",
                    "r2dbc:mysql://localhost:3306/buddystudy?serverZoneId=UTC",
                )

        val settings = FlywayDataSourceSettings.from(environment)

        assertThat(settings.url)
            .isEqualTo("jdbc:mysql://localhost:3306/buddystudy?serverTimezone=UTC")
    }

    @Test
    fun `prefers dedicated Flyway settings`() {
        val environment =
            MockEnvironment()
                .withProperty("spring.flyway.url", "jdbc:mysql://db:3306/migrations")
                .withProperty("spring.flyway.user", "flyway-user")
                .withProperty("spring.flyway.password", "flyway-password")
                .withProperty("DATABASE_USERNAME", "database-user")
                .withProperty("DATABASE_PASSWORD", "database-password")
                .withProperty("spring.r2dbc.username", "r2dbc-user")
                .withProperty("spring.r2dbc.password", "r2dbc-password")

        val settings = FlywayDataSourceSettings.from(environment)

        assertThat(settings.url).isEqualTo("jdbc:mysql://db:3306/migrations")
        assertThat(settings.username).isEqualTo("flyway-user")
        assertThat(settings.password).isEqualTo("flyway-password")
    }

    @Test
    fun `creates an explicit MySQL data source for native runtime`() {
        val settings =
            FlywayDataSourceSettings(
                url = "jdbc:mysql://db:3306/migrations?useSSL=true",
                username = "flyway-user",
                password = "flyway-password",
            )

        val dataSource = settings.toDataSource()

        assertThat(dataSource.getURL()).isEqualTo("jdbc:mysql://db:3306/migrations?useSSL=true")
        assertThat(dataSource.user).isEqualTo("flyway-user")
        assertThat(dataSource.password).isEqualTo("flyway-password")
    }

    @Test
    fun `ignores blank username overrides`() {
        val environment =
            MockEnvironment()
                .withProperty("spring.flyway.user", "")
                .withProperty("DATABASE_USERNAME", "")
                .withProperty("spring.r2dbc.username", "buddystudy")

        val settings = FlywayDataSourceSettings.from(environment)

        assertThat(settings.username).isEqualTo("buddystudy")
    }
}
