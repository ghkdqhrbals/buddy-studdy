package com.buddystudy.backend

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant

class DeviceSessionOwnershipMigrationIntegrationTest {
    @Test
    fun `V79 revokes only active sessions that do not own their device`() {
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.4")
            .withDatabaseName("buddystudy_device_session_migration")
            .withUsername("buddystudy")
            .withPassword("buddystudy")

        mysql.start()
        try {
            flyway(mysql, target = "78").migrate()
            val seeded = mysql.connection().use(::seedSessions)

            val result = flyway(mysql, target = "79").migrate()

            mysql.connection().use { connection ->
                assertThat(result.migrationsExecuted).isEqualTo(1)
                assertThat(connection.revokedAt(seeded.staleActiveSessionId)).isNotNull()
                assertThat(connection.revokedAt(seeded.detachedActiveSessionId)).isNotNull()
                assertThat(connection.revokedAt(seeded.currentOwnerSessionId)).isNull()
                assertThat(connection.revokedAt(seeded.loggedOutSessionId)).isNull()
                assertThat(connection.revokedAt(seeded.expiredSessionId)).isNull()
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

    private fun seedSessions(connection: Connection): SeededSessions {
        val oldOwner = connection.insertUser("old-owner")
        val currentOwner = connection.insertUser("current-owner")
        val anotherUser = connection.insertUser("another-user")
        connection.insertDevice("shared-device", currentOwner)
        connection.insertDevice("detached-device", null)

        return SeededSessions(
            staleActiveSessionId = connection.insertSession(oldOwner, "shared-device"),
            currentOwnerSessionId = connection.insertSession(currentOwner, "shared-device"),
            detachedActiveSessionId = connection.insertSession(anotherUser, "detached-device"),
            loggedOutSessionId = connection.insertSession(
                oldOwner,
                "detached-device",
                loggedOutAt = Instant.now().minusSeconds(60),
            ),
            expiredSessionId = connection.insertSession(
                anotherUser,
                "shared-device",
                expiresAt = Instant.now().minusSeconds(60),
            ),
        )
    }

    private fun Connection.insertUser(suffix: String): Long {
        val sql = """
            insert into users (
                provider, provider_id, status, email, display_name, created_at, updated_at
            ) values (
                'EMAIL', ?, 'ACTIVE', ?, ?, utc_timestamp(6), utc_timestamp(6)
            )
        """.trimIndent()
        return prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setString(1, "device-session-$suffix")
            statement.setString(2, "device-session-$suffix@example.com")
            statement.setString(3, "Device Session $suffix")
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Expected a generated user id." }
                keys.getLong(1)
            }
        }
    }

    private fun Connection.insertDevice(deviceId: String, userId: Long?) {
        val sql = """
            insert into devices (
                device_id, client_secret_hash, user_id, apns_token, platform,
                apns_environment, language, timezone, created_at, updated_at, last_seen_at
            ) values (
                ?, 'secret', ?, 'token', 'ios', 'production', 'ko', 'Asia/Seoul',
                utc_timestamp(6), utc_timestamp(6), utc_timestamp(6)
            )
        """.trimIndent()
        prepareStatement(sql).use { statement ->
            statement.setString(1, deviceId)
            if (userId == null) statement.setNull(2, java.sql.Types.BIGINT) else statement.setLong(2, userId)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertSession(
        userId: Long,
        deviceId: String,
        loggedOutAt: Instant? = null,
        expiresAt: Instant? = Instant.now().plusSeconds(86_400),
    ): Long {
        val sql = """
            insert into user_devices (
                user_id, device_id, session_expires_at, last_login_at, last_seen_at,
                logged_out_at, revoked_at, created_at, updated_at
            ) values (?, ?, ?, utc_timestamp(6), utc_timestamp(6), ?, null, utc_timestamp(6), utc_timestamp(6))
        """.trimIndent()
        return prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, deviceId)
            if (expiresAt == null) statement.setNull(3, java.sql.Types.TIMESTAMP) else statement.setTimestamp(3, Timestamp.from(expiresAt))
            if (loggedOutAt == null) statement.setNull(4, java.sql.Types.TIMESTAMP) else statement.setTimestamp(4, Timestamp.from(loggedOutAt))
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Expected a generated session id." }
                keys.getLong(1)
            }
        }
    }

    private fun Connection.revokedAt(sessionId: Long): Timestamp? =
        prepareStatement("select revoked_at from user_devices where id = ?").use { statement ->
            statement.setLong(1, sessionId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Expected session $sessionId." }
                rows.getTimestamp(1)
            }
        }

    private data class SeededSessions(
        val staleActiveSessionId: Long,
        val currentOwnerSessionId: Long,
        val detachedActiveSessionId: Long,
        val loggedOutSessionId: Long,
        val expiredSessionId: Long,
    )
}
