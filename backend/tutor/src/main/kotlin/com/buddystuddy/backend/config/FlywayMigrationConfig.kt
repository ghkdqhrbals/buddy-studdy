package com.buddystuddy.backend.config

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import javax.sql.DataSource

@Configuration
@ConditionalOnClass(Flyway::class)
@ConditionalOnProperty(prefix = "spring.flyway", name = ["enabled"], havingValue = "true")
class FlywayMigrationConfig {
    @Bean
    fun flyway(dataSource: DataSource, environment: Environment): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
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
    fun flywayMigration(flyway: Flyway): MigrateResult = flyway.migrate()

    companion object {
        private fun Environment.getBooleanProperty(vararg names: String, defaultValue: Boolean): Boolean =
            names.firstNotNullOfOrNull { getProperty(it, Boolean::class.java) } ?: defaultValue

        @Bean
        @JvmStatic
        fun entityManagerFactoryDependsOnFlyway(): BeanFactoryPostProcessor =
            BeanFactoryPostProcessor { beanFactory ->
                if (beanFactory.containsBeanDefinition("entityManagerFactory")) {
                    val definition = beanFactory.getBeanDefinition("entityManagerFactory")
                    definition.setDependsOn(*(definition.dependsOn ?: emptyArray()) + "flywayMigration")
                }
            }
    }
}
