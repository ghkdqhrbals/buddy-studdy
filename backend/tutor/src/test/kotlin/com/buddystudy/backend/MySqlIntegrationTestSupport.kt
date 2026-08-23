package com.buddystudy.backend

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer

abstract class MySqlIntegrationTestSupport {
    companion object {
        private val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.4")
            .withDatabaseName("buddystudy")
            .withUsername("buddystudy")
            .withPassword("buddystudy")
            .also { it.start() }

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") {
                "r2dbc:mysql://${mysql.host}:${mysql.firstMappedPort}/${mysql.databaseName}?serverZoneId=UTC"
            }
            registry.add("spring.r2dbc.username", mysql::getUsername)
            registry.add("spring.r2dbc.password", mysql::getPassword)
            registry.add("spring.flyway.url", mysql::getJdbcUrl)
            registry.add("spring.flyway.user", mysql::getUsername)
            registry.add("spring.flyway.password", mysql::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration-mysql" }
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.flyway.validate-on-migrate") { false }
            registry.add("buddystudy.analytics.datasource.database-name") { "" }
            registry.add("buddystudy.email.host") { "smtp.test.invalid" }
            registry.add("buddystudy.email.port") { 587 }
            registry.add("buddystudy.email.username") { "test@invalid.example" }
            registry.add("buddystudy.email.password") { "test-only" }
            registry.add("buddystudy.email.from") { "BuddyStudy <test@invalid.example>" }
        }
    }
}
