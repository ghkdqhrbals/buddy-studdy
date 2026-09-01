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

class VoiceTutorResultClaimMigrationIntegrationTest {
    @Test
    fun `V108 expands rolling claim compatibility while constraining token-aware writes`() {
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.4")
            .withDatabaseName("buddystudy_voice_result_claim_migration")
            .withUsername("buddystudy")
            .withPassword("buddystudy")

        mysql.start()
        try {
            flyway(mysql, target = "107").migrate()
            mysql.connection().use(::seedResults)

            val result = flyway(mysql, target = "108").migrate()

            mysql.connection().use { connection ->
                assertThat(result.migrationsExecuted).isEqualTo(1)
                assertThat(connection.nullableStringValue(
                    "select claim_token from voice_tutor_results where session_id = 'claim-processing'",
                )).isNull()
                assertThat(connection.nullableStringValue(
                    "select claim_token from voice_tutor_results where session_id = 'claim-completed'",
                )).isNull()
                assertThat(connection.nullableStringValue(
                    "select claim_token from voice_tutor_results where session_id = 'claim-failed'",
                )).isNull()

                // A legacy PROCESSING owner remains valid throughout a
                // start-first rollout and may finish without a token.
                assertThat(connection.execute(
                    "update voice_tutor_results set status = 'COMPLETED' " +
                        "where session_id = 'claim-processing'",
                )).isEqualTo(1)
                assertThat(connection.nullableStringValue(
                    "select claim_token from voice_tutor_results where session_id = 'claim-processing'",
                )).isNull()

                val tokenAwareClaim = UUID.randomUUID().toString()
                assertThat(connection.execute(
                    "update voice_tutor_results set status = 'PROCESSING', " +
                        "claim_token = '$tokenAwareClaim' where session_id = 'claim-failed'",
                )).isEqualTo(1)
                assertThat(connection.stringValue(
                    "select claim_token from voice_tutor_results where session_id = 'claim-failed'",
                )).isEqualTo(tokenAwareClaim)

                // An old terminal statement does not clear a token-aware claim,
                // so the compatibility constraint still fails it closed.
                assertThatThrownBy {
                    connection.execute(
                        "update voice_tutor_results set status = 'FAILED' " +
                            "where session_id = 'claim-failed'",
                    )
                }.isInstanceOf(SQLException::class.java)
                assertThat(connection.execute(
                    "update voice_tutor_results set status = 'FAILED', claim_token = null " +
                        "where session_id = 'claim-failed'",
                )).isEqualTo(1)
                assertThat(connection.stringValue(
                    "select status from voice_tutor_results where session_id = 'claim-failed'",
                )).isEqualTo("FAILED")
                assertThat(connection.nullableStringValue(
                    "select claim_token from voice_tutor_results where session_id = 'claim-failed'",
                )).isNull()

                assertThatThrownBy {
                    connection.execute(
                        "update voice_tutor_results set claim_token = '${UUID.randomUUID()}' " +
                            "where session_id = 'claim-completed'",
                    )
                }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy {
                    connection.execute(
                        "update voice_tutor_results set status = 'PROCESSING', claim_token = 'short' " +
                            "where session_id = 'claim-completed'",
                    )
                }.isInstanceOf(SQLException::class.java)
            }
        } finally {
            mysql.stop()
        }
    }

    private fun flyway(mysql: MySQLContainer<*>, target: String): Flyway =
        Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration-mysql")
            .target(MigrationVersion.fromVersion(target))
            .load()

    private fun MySQLContainer<*>.connection(): Connection =
        DriverManager.getConnection(jdbcUrl, username, password)

    private fun seedResults(connection: Connection) {
        val userId = connection.generatedId(
            """
            insert into users (
                provider, provider_id, status, email, display_name, created_at, updated_at
            ) values (
                'EMAIL', 'voice-claim-migration', 'ACTIVE',
                'voice-claim-migration@example.com', 'Voice Claim Migration',
                utc_timestamp(6), utc_timestamp(6)
            )
            """.trimIndent(),
        )
        connection.insertSession(userId, "claim-processing", "PROCESSING")
        connection.insertSession(userId, "claim-completed", "COMPLETED")
        connection.insertSession(userId, "claim-failed", "FAILED")
        connection.execute(
            """
            insert into voice_tutor_results (
                session_id, status, prompt_version, created_at, updated_at
            ) values
                ('claim-processing', 'PROCESSING', 'migration-v1', utc_timestamp(6), utc_timestamp(6)),
                ('claim-completed', 'COMPLETED', 'migration-v1', utc_timestamp(6), utc_timestamp(6)),
                ('claim-failed', 'FAILED', 'migration-v1', utc_timestamp(6), utc_timestamp(6))
            """.trimIndent(),
        )
    }

    private fun Connection.insertSession(userId: Long, sessionId: String, resultStatus: String) {
        prepareStatement(
            """
            insert into voice_tutor_sessions (
                id, user_id, study_id, idempotency_key, provider_session_id,
                status, result_status, language, model, voice, topic_snapshot,
                difficulty_snapshot, period_started_at, period_ends_at,
                reserved_seconds, charged_seconds, max_session_seconds, hard_ends_at,
                connected_at, relay_heartbeat_at, accepted_audio_bytes, ended_at,
                finalized_at, finalization_key, end_reason, failure_code,
                failure_message, created_at, updated_at
            ) values (
                ?, ?, null, ?, null,
                'COMPLETED', ?, 'ko', 'gpt-test', 'marin', 'Migration fixture',
                5, utc_timestamp(6), timestampadd(day, 30, utc_timestamp(6)),
                60, 1, 60, timestampadd(minute, 1, utc_timestamp(6)),
                utc_timestamp(6), utc_timestamp(6), 0, utc_timestamp(6),
                utc_timestamp(6), null, 'USER_ENDED', null,
                null, utc_timestamp(6), utc_timestamp(6)
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setLong(2, userId)
            statement.setString(3, "idempotency-$sessionId")
            statement.setString(4, resultStatus)
            statement.executeUpdate()
        }
    }

    private fun Connection.generatedId(sql: String): Long =
        createStatement().use { statement ->
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS)
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Expected a generated id." }
                keys.getLong(1)
            }
        }

    private fun Connection.execute(sql: String): Int =
        createStatement().use { statement -> statement.executeUpdate(sql) }

    private fun Connection.stringValue(sql: String): String =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next()) { "Expected one row." }
                result.getString(1)
            }
        }

    private fun Connection.nullableStringValue(sql: String): String? =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next()) { "Expected one row." }
                result.getString(1)
            }
        }
}
