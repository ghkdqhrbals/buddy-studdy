package com.buddystudy.backend

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.MySQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

class PushCampaignMigrationUpgradeIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `push V124 upgrades deployed V123 without replacing its billing migration`() {
        val localPort = System.getenv("BUDDYSTUDY_TEST_MYSQL_PORT")?.toInt()?.also {
            require(it in 1024..65535 && it != 3306)
        }
        val database = "buddystudy_push_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        val mysql: MySQLContainer<*>? = if (localPort == null) MySQLContainer("mysql:8.4")
            .withDatabaseName(database).withUsername("buddystudy").withPassword("buddystudy").also { it.start() }
        else null
        val adminUrl = "jdbc:mysql://127.0.0.1:$localPort/?serverTimezone=UTC"
        val url = mysql?.jdbcUrl ?: "jdbc:mysql://127.0.0.1:$localPort/$database?serverTimezone=UTC"
        val user = mysql?.username ?: "root"
        val password = mysql?.password ?: ""
        try {
            if (localPort != null) DriverManager.getConnection(adminUrl, user, password).use {
                it.createStatement().use { statement -> statement.executeUpdate("create database $database character set utf8mb4 collate utf8mb4_0900_ai_ci") }
            }
            val source = Path.of("src/main/resources/db/migration-mysql")
            Files.list(source).use { paths -> paths.filter { it.fileName.toString() != "V124__push_campaigns.sql" }.forEach {
                Files.copy(it, temporary.resolve(it.fileName))
            } }
            fun configured(location: String) = Flyway.configure().dataSource(url, user, password).locations(location)
            val baseline = configured("filesystem:$temporary").target(MigrationVersion.fromVersion("123")).load()
            baseline.migrate()
            assertThat(baseline.info().current().version.toString()).isEqualTo("123")

            // Reproduce the rejected rollout: an already-applied version cannot be repurposed.
            Files.delete(temporary.resolve("V123__ignore_local_storekit_revenuecat_events.sql"))
            Files.copy(source.resolve("V124__push_campaigns.sql"), temporary.resolve("V123__push_campaigns.sql"))
            val rejected = configured("filesystem:$temporary").load().validateWithResult()
            assertThat(rejected.validationSuccessful).isFalse()
            assertThat(rejected.invalidMigrations.map { it.version }).contains("123")

            val corrected = configured("classpath:db/migration-mysql").load()
            assertThat(corrected.migrate().migrationsExecuted).isEqualTo(1)
            assertThat(corrected.validateWithResult().validationSuccessful).isTrue()
            assertThat(corrected.info().current().version.toString()).isEqualTo("124")
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select description from flyway_schema_history where version = '123'").use {
                        assertThat(it.next()).isTrue()
                        assertThat(it.getString(1)).isEqualTo("ignore local storekit revenuecat events")
                    }
                    statement.executeQuery("select count(*) from push_campaigns").use {
                        assertThat(it.next()).isTrue()
                        assertThat(it.getInt(1)).isZero()
                    }
                }
            }
        } finally {
            if (localPort != null) DriverManager.getConnection(adminUrl, user, password).use {
                it.createStatement().use { statement -> statement.executeUpdate("drop database if exists $database") }
            }
            mysql?.stop()
        }
    }
}
