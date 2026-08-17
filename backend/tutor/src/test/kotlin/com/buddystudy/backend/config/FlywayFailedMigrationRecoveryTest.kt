package com.buddystudy.backend.config

import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test

class FlywayFailedMigrationRecoveryTest {
    @Test
    fun `removes only the known failed V78 history row`() {
        val dataSource = dataSource("known_failure")
        createHistoryTable(dataSource)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into flyway_schema_history (
                        installed_rank, version, description, script, success
                    ) values
                        (1, '77', 'previous migration', 'V77__previous.sql', true),
                        (2, '78', 'remove unused legacy default studies',
                         'V78__remove_unused_legacy_default_studies.sql', false),
                        (3, '79', 'unrelated failure', 'V79__unrelated.sql', false)
                    """.trimIndent(),
                )
            }
        }

        val removed = FlywayFailedMigrationRecovery.recover(dataSource, "flyway_schema_history")

        assertThat(removed).isEqualTo(1)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select version, success from flyway_schema_history order by installed_rank",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("version")).isEqualTo("77")
                    assertThat(rows.getBoolean("success")).isTrue()
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("version")).isEqualTo("79")
                    assertThat(rows.getBoolean("success")).isFalse()
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    @Test
    fun `does nothing before the Flyway history table exists`() {
        val dataSource = dataSource("missing_history")

        assertThat(
            FlywayFailedMigrationRecovery.recover(dataSource, "flyway_schema_history"),
        ).isZero()
    }

    private fun dataSource(name: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:$name;MODE=MySQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }

    private fun createHistoryTable(dataSource: JdbcDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    create table flyway_schema_history (
                        installed_rank int primary key,
                        version varchar(50),
                        description varchar(200) not null,
                        script varchar(1000) not null,
                        success boolean not null
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
