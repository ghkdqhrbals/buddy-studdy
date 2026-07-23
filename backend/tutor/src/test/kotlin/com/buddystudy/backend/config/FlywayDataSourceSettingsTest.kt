package com.buddystudy.backend.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class FlywayDataSourceSettingsTest {
    @Test
    fun `uses effective R2DBC settings when Flyway and environment credentials are absent`() {
        val environment =
            MockEnvironment()
                .withProperty("spring.r2dbc.url", "r2dbc:postgresql://localhost:5432/buddystudy")
                .withProperty("spring.r2dbc.username", "buddystudy")
                .withProperty("spring.r2dbc.password", "local-password")

        val settings = FlywayDataSourceSettings.from(environment)

        assertThat(settings.url).isEqualTo("jdbc:postgresql://localhost:5432/buddystudy")
        assertThat(settings.username).isEqualTo("buddystudy")
        assertThat(settings.password).isEqualTo("local-password")
    }

    @Test
    fun `prefers dedicated Flyway settings`() {
        val environment =
            MockEnvironment()
                .withProperty("spring.flyway.url", "jdbc:postgresql://db:5432/migrations")
                .withProperty("spring.flyway.user", "flyway-user")
                .withProperty("spring.flyway.password", "flyway-password")
                .withProperty("DATABASE_USERNAME", "database-user")
                .withProperty("DATABASE_PASSWORD", "database-password")
                .withProperty("spring.r2dbc.username", "r2dbc-user")
                .withProperty("spring.r2dbc.password", "r2dbc-password")

        val settings = FlywayDataSourceSettings.from(environment)

        assertThat(settings.url).isEqualTo("jdbc:postgresql://db:5432/migrations")
        assertThat(settings.username).isEqualTo("flyway-user")
        assertThat(settings.password).isEqualTo("flyway-password")
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
