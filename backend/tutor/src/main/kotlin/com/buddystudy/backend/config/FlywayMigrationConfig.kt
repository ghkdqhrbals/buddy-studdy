package com.buddystudy.backend.config

import org.flywaydb.core.Flyway
import com.mysql.cj.jdbc.MysqlDataSource
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

@Configuration
@ConditionalOnClass(Flyway::class)
class FlywayMigrationConfig {
    @Bean
    fun flyway(environment: Environment): Flyway {
        val dataSource = FlywayDataSourceSettings.from(environment).toDataSource()
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(*environment.getProperty("spring.flyway.locations", "classpath:db/migration-mysql").split(",").map { it.trim() }.toTypedArray())
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
            // The first production V78 attempt referenced an outbox table removed by V34.
            // Remove only that exact failed history row so the corrected migration can retry.
            FlywayFailedMigrationRecovery.recover(
                dataSource = flyway.configuration.dataSource,
                historyTable = flyway.configuration.table,
            )
            flyway.migrate()
        } else {
            "flyway-disabled"
        }

    companion object {
        private fun Environment.getBooleanProperty(vararg names: String, defaultValue: Boolean): Boolean =
            names.firstNotNullOfOrNull { getProperty(it, Boolean::class.java) } ?: defaultValue
    }
}

internal object FlywayFailedMigrationRecovery {
    private val logger = LoggerFactory.getLogger(FlywayFailedMigrationRecovery::class.java)
    private val safeIdentifier = Regex("[A-Za-z0-9_]+")

    private const val VERSION = "78"
    private const val DESCRIPTION = "remove unused legacy default studies"
    private const val SCRIPT = "V78__remove_unused_legacy_default_studies.sql"

    fun recover(dataSource: DataSource, historyTable: String): Int {
        require(safeIdentifier.matches(historyTable)) {
            "Unsupported Flyway schema history table name: $historyTable"
        }

        return dataSource.connection.use { connection ->
            if (!historyTableExists(connection, historyTable)) {
                return@use 0
            }
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val deleted =
                    connection.prepareStatement(
                        """
                        delete from `$historyTable`
                        where version = ?
                          and description = ?
                          and script = ?
                          and success = false
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, VERSION)
                        statement.setString(2, DESCRIPTION)
                        statement.setString(3, SCRIPT)
                        statement.executeUpdate()
                    }
                connection.commit()
                if (deleted > 0) {
                    logger.warn(
                        "flyway_known_failed_migration_removed version={} script={} rows={}",
                        VERSION,
                        SCRIPT,
                        deleted,
                    )
                }
                deleted
            } catch (error: SQLException) {
                connection.rollback()
                if (error.sqlState == "42S02" || error.errorCode == 1146) {
                    0
                } else {
                    throw error
                }
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private fun historyTableExists(connection: Connection, historyTable: String): Boolean {
        val catalog = connection.catalog
        val schema = connection.schema
        val patterns = listOf(historyTable, historyTable.uppercase()).distinct()
        for (pattern in patterns) {
            connection.metaData.getTables(catalog, schema, pattern, arrayOf("TABLE")).use { tables ->
                while (tables.next()) {
                    if (tables.getString("TABLE_NAME").equals(historyTable, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}

internal data class FlywayDataSourceSettings(
    val url: String,
    val username: String,
    val password: String,
) {
    fun toDataSource(): MysqlDataSource =
        MysqlDataSource().also {
            it.setURL(url)
            it.user = username
            it.setPassword(password)
        }

    companion object {
        fun from(environment: Environment): FlywayDataSourceSettings =
            FlywayDataSourceSettings(
                url =
                    environment.firstNonBlank("spring.flyway.url", "DATABASE_URL")
                        ?: environment.firstNonBlank("spring.r2dbc.url")?.toJdbcUrl()
                        ?: "jdbc:mysql://localhost:3306/buddystudy?serverTimezone=UTC",
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
                startsWith("r2dbc:mysql:") ->
                    replaceFirst("r2dbc:mysql:", "jdbc:mysql:")
                        .replace("serverZoneId=", "serverTimezone=")
                startsWith("r2dbc:") -> removePrefix("r2dbc:").let { "jdbc:$it" }
                else -> this
            }
    }
}
