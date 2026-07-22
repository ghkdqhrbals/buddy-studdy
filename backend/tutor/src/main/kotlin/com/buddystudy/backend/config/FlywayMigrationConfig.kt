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
    fun flyway(environment: Environment): Flyway =
        Flyway.configure()
            .dataSource(
                environment.getProperty("spring.flyway.url")
                    ?: environment.getProperty("DATABASE_URL")
                    ?: "jdbc:postgresql://localhost:5432/buddystudy",
                environment.getProperty("spring.flyway.user")
                    ?: environment.getProperty("DATABASE_USERNAME", ""),
                environment.getProperty("spring.flyway.password")
                    ?: environment.getProperty("DATABASE_PASSWORD", ""),
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
