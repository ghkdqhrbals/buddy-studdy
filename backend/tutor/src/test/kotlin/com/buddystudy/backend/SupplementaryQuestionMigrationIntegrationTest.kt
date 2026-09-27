package com.buddystudy.backend

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID

class SupplementaryQuestionMigrationIntegrationTest {
    @Test
    fun `V121 and V122 preserve deployed voice and ordinary records while enforcing supplementary shapes`() {
        val localPort = System.getenv("BUDDYSTUDY_TEST_MYSQL_PORT")?.toInt()?.also {
            require(it in 1024..65535 && it != 3306)
        }
        val database = "buddystudy_supplementary_${UUID.randomUUID().toString().replace("-", "")}"
        val mysql: MySQLContainer<*>? = if (localPort == null) MySQLContainer("mysql:8.4")
            .withDatabaseName(database).withUsername("buddystudy").withPassword("buddystudy").also { it.start() }
        else null
        val adminUrl = "jdbc:mysql://127.0.0.1:$localPort/?serverTimezone=UTC"
        val url = mysql?.jdbcUrl ?: "jdbc:mysql://127.0.0.1:$localPort/$database?serverTimezone=UTC"
        val user = mysql?.username ?: "root"
        val password = mysql?.password ?: ""
        try {
            if (localPort != null) DriverManager.getConnection(adminUrl, user, password).use {
                it.execute("create database $database character set utf8mb4 collate utf8mb4_0900_ai_ci")
            }
            fun migrate(target: String) = Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration-mysql").target(MigrationVersion.fromVersion(target)).load().migrate()
            migrate("120")
            DriverManager.getConnection(url, user, password).use { connection ->
                val original = connection.insertRecord("QUESTION", "manual", "graded", score = 82)
                // This tests the deployed SQL voice shape independently of extension read eligibility.
                val voice = connection.insertRecord("VOICE_TUTOR", "voice_tutor", "completed", score = 71)
                assertThat(migrate("122").migrationsExecuted).isEqualTo(2)
                connection.createStatement().use { statement ->
                    statement.executeQuery("select source, record_type, status, score, follow_up_depth, parent_record_id, root_record_id from questions where id = $voice").use { row ->
                        assertThat(row.next()).isTrue()
                        assertThat(row.getString("source")).isEqualTo("voice_tutor")
                        assertThat(row.getString("record_type")).isEqualTo("VOICE_TUTOR")
                        assertThat(row.getString("status")).isEqualTo("completed")
                        assertThat(row.getInt("score")).isEqualTo(71)
                        assertThat(row.getInt("follow_up_depth")).isZero()
                        assertThat(row.getObject("parent_record_id")).isNull()
                        assertThat(row.getObject("root_record_id")).isNull()
                    }
                }
                val custom = connection.insertRecord("QUESTION", "custom_question", "graded")
                assertThatThrownBy { connection.execute("update questions set score = 1 where id = $custom") }
                    .isInstanceOf(SQLException::class.java)
                assertThatThrownBy { connection.execute("update questions set is_public = true where id = $custom") }
                    .isInstanceOf(SQLException::class.java)
                val followUp = connection.insertRecord("QUESTION", "follow_up", "ungraded", parent = original)
                assertThatThrownBy { connection.execute("update questions set is_public = true where id = $followUp") }
                    .isInstanceOf(SQLException::class.java)
                assertThatThrownBy { connection.insertRecord("QUESTION", "follow_up", "ungraded", parent = original) }
                    .isInstanceOf(SQLException::class.java)
                assertThatThrownBy { connection.execute("update questions set source = 'manual' where id = $voice") }
                    .isInstanceOf(SQLException::class.java)
                assertThat(connection.execute("update questions set score = 83 where id = $original")).isEqualTo(1)
            }
        } finally {
            if (localPort != null) DriverManager.getConnection(adminUrl, user, password).use {
                it.execute("drop database if exists $database")
            }
            mysql?.stop()
        }
    }

    private fun Connection.insertRecord(type: String, source: String, status: String, score: Int? = null, parent: Long? = null): Long {
        val columns = if (parent == null) "" else ", parent_record_id, root_record_id, follow_up_depth"
        val values = if (parent == null) "" else ", $parent, $parent, 1"
        return prepareStatement("""
            insert into questions (device_id, record_type, source_language, question, answer, topic, difficulty_level,
                scheduled_for, status, source, score, is_public, created_at, updated_at$columns)
            values ('', '$type', 'en', 'Migration question', 'Migration answer', 'Redis', 3,
                utc_timestamp(6), '$status', '$source', ${score ?: "null"}, false,
                utc_timestamp(6), utc_timestamp(6)$values)
        """.trimIndent(), Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.executeUpdate()
            statement.generatedKeys.use { keys -> check(keys.next()); keys.getLong(1) }
        }
    }

    private fun Connection.execute(sql: String): Int = createStatement().use { it.executeUpdate(sql) }
}
