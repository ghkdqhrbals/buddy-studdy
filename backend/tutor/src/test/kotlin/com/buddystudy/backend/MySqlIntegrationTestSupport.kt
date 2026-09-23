package com.buddystudy.backend

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer

abstract class MySqlIntegrationTestSupport {
    companion object {
        // Explicit local-only fallback for an isolated developer-owned mysqld when Docker is
        // unavailable. It never targets the default port or a remotely supplied host/database.
        private val localPort = System.getenv("BUDDYSTUDY_TEST_MYSQL_PORT")?.toInt()?.also {
            require(it in 1024..65535 && it != 3306) { "Use an isolated MySQL test port, not 3306." }
        }
        private val mysql: MySQLContainer<*>? = if (localPort != null) null else MySQLContainer("mysql:8.4")
            .withDatabaseName("buddystudy")
            .withUsername("buddystudy")
            .withPassword("buddystudy")
            .also { it.start() }

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") {
                if (localPort != null) "r2dbc:mysql://127.0.0.1:$localPort/buddystudy_test?serverZoneId=UTC"
                else "r2dbc:mysql://${mysql!!.host}:${mysql.firstMappedPort}/${mysql.databaseName}?serverZoneId=UTC"
            }
            registry.add("spring.r2dbc.username") { mysql?.username ?: "root" }
            registry.add("spring.r2dbc.password") { mysql?.password ?: "" }
            registry.add("spring.flyway.url") {
                mysql?.jdbcUrl ?: "jdbc:mysql://127.0.0.1:$localPort/buddystudy_test?serverTimezone=UTC"
            }
            registry.add("spring.flyway.user") { mysql?.username ?: "root" }
            registry.add("spring.flyway.password") { mysql?.password ?: "" }
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
