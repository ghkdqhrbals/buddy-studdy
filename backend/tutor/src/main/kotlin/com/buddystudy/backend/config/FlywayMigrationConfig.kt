package com.buddystudy.backend.config

import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration
@ConditionalOnClass(Flyway::class)
class FlywayMigrationConfig {
    @Bean
    fun flyway(environment: Environment): Flyway {
        val dataSource = FlywayDataSourceSettings.from(environment)
        return Flyway.configure()
            .dataSource(
                dataSource.url,
                dataSource.username,
                dataSource.password,
            )
            .locations(*environment.getProperty("spring.flyway.locations", "classpath:db/migration").split(",").map { it.trim() }.toTypedArray())
            .baselineOnMigrate(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean::class.java, true))
            .baselineVersion(environment.getProperty("spring.flyway.baseline-version", "0"))
            .validateOnMigrate(
                environment.getBooleanProperty(
                    "spring.flyway.validate-on-migrate",
                    "SPRING_FLYWAY_VALIDATE_ON_MIGRATE",
                    "FLYWAY_VALIDATE_ON_MIGRATE",
                    defaultValue = true,
                ),
            )
            .load()
    }

    @Bean
    fun flywayMigration(flyway: Flyway, environment: Environment): Any =
        if (
            environment.getBooleanProperty(
                "spring.flyway.enabled",
                "SPRING_FLYWAY_ENABLED",
                "FLYWAY_ENABLED",
                defaultValue = false,
            )
        ) {
            flyway.migrate()
        } else {
            "flyway-disabled"
        }

    companion object {
        private fun Environment.getBooleanProperty(vararg names: String, defaultValue: Boolean): Boolean =
            names.firstNotNullOfOrNull { getProperty(it, Boolean::class.java) } ?: defaultValue
    }
}

internal data class FlywayDataSourceSettings(
    val url: String,
    val username: String,
    val password: String,
) {
    companion object {
        fun from(environment: Environment): FlywayDataSourceSettings =
            FlywayDataSourceSettings(
                url =
                    environment.firstNonBlank("spring.flyway.url", "DATABASE_URL")
                        ?: environment.firstNonBlank("spring.r2dbc.url")?.toJdbcUrl()
                        ?: "jdbc:postgresql://localhost:5432/buddystudy",
                username =
                    environment.firstNonBlank(
                        "spring.flyway.user",
                        "DATABASE_USERNAME",
                        "spring.r2dbc.username",
                    ).orEmpty(),
                password =
                    environment.firstDefined(
                        "spring.flyway.password",
                        "DATABASE_PASSWORD",
                        "spring.r2dbc.password",
                    ).orEmpty(),
            )

        private fun Environment.firstNonBlank(vararg names: String): String? =
            names.firstNotNullOfOrNull { getProperty(it)?.takeIf(String::isNotBlank) }

        private fun Environment.firstDefined(vararg names: String): String? =
            names.firstNotNullOfOrNull(::getProperty)

        private fun String.toJdbcUrl(): String =
            when {
                startsWith("r2dbc:postgresql:") -> replaceFirst("r2dbc:postgresql:", "jdbc:postgresql:")
                startsWith("r2dbc:") -> removePrefix("r2dbc:").let { "jdbc:$it" }
                else -> this
            }
    }
}
