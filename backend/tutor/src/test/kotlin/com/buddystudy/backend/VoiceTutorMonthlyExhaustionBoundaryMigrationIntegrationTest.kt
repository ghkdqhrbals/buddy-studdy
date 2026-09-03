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
import java.sql.Types
import java.time.Instant

class VoiceTutorMonthlyExhaustionBoundaryMigrationIntegrationTest {
    @Test
    fun `V115 backfills only provable finite monthly exhaustion boundaries`() {
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.4")
            .withDatabaseName("buddystudy_voice_monthly_boundary_migration")
            .withUsername("buddystudy")
            .withPassword("buddystudy")

        mysql.start()
        try {
            flyway(mysql, target = "114").migrate()
            mysql.connection().use(::seedUpgradeState)

            val result = flyway(mysql, target = "116").migrate()

            mysql.connection().use { connection ->
                assertThat(result.migrationsExecuted).isEqualTo(2)
                assertThat(connection.boundary("finite-ready")).isTrue()
                assertThat(connection.boundary("finite-active-rtc")).isTrue()

                assertThat(connection.boundary("per-call-cap")).isFalse()
                assertThat(connection.boundary("effectively-unlimited")).isFalse()
                assertThat(connection.boundary("quota-reservation-mismatch")).isFalse()
                assertThat(connection.boundary("active-non-rtc")).isFalse()
                assertThat(connection.boundary("legacy-reservation-anchored-active")).isFalse()
                assertThat(connection.boundary("terminal-session")).isFalse()
                assertThat(connection.nullBoundaryCount()).isZero()
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

    private fun seedUpgradeState(connection: Connection) {
        val periodStartedAt = Instant.parse("2031-08-01T00:00:00Z")
        val periodEndsAt = Instant.parse("2031-09-01T00:00:00Z")
        val createdAt = Instant.parse("2031-08-10T00:00:00Z")

        connection.seedBoundary(
            "finite-ready",
            baseSeconds = 600,
            usedSeconds = 300,
            quotaReservedSeconds = 300,
            sessionReservedSeconds = 300,
            status = "READY",
            providerSessionId = null,
            connectedAt = null,
            createdAt = createdAt,
            hardEndsAt = createdAt.plusSeconds(300),
            periodStartedAt = periodStartedAt,
            periodEndsAt = periodEndsAt,
        )
        connection.seedBoundary(
            "finite-active-rtc",
            baseSeconds = 3_600,
            usedSeconds = 0,
            quotaReservedSeconds = 3_600,
            sessionReservedSeconds = 3_600,
            status = "ACTIVE",
            providerSessionId = "rtc_finite_active",
            connectedAt = createdAt.plusSeconds(20),
            createdAt = createdAt,
            hardEndsAt = createdAt.plusSeconds(3_620),
            periodStartedAt = periodStartedAt,
            periodEndsAt = periodEndsAt,
        )
        connection.seedBoundary(
            "per-call-cap",
            baseSeconds = 7_200,
            usedSeconds = 0,
            quotaReservedSeconds = 3_600,
            sessionReservedSeconds = 3_600,
            status = "ACTIVE",
            providerSessionId = "rtc_per_call_cap",
            connectedAt = createdAt,
            createdAt = createdAt,
            hardEndsAt = createdAt.plusSeconds(3_600),
            periodStartedAt = periodStartedAt,
            periodEndsAt = periodEndsAt,
        )
        connection.seedBoundary(
            "effectively-unlimited",
            baseSeconds = 31_536_000,
            usedSeconds = 31_532_400,
            quotaReservedSeconds = 3_600,
            sessionReservedSeconds = 3_600,
            status = "ACTIVE",
            providerSessionId = "rtc_effectively_unlimited",
            connectedAt = createdAt,
            createdAt = createdAt,
            hardEndsAt = createdAt.plusSeconds(3_600),
            periodStartedAt = periodStartedAt,
            periodEndsAt = periodEndsAt,
            limitOverrideSeconds = 31_536_000,
        )
        connection.seedBoundary(
            "quota-reservation-mismatch",
            baseSeconds = 600,
            usedSeconds = 300,
            quotaReservedSeconds = 300,
            sessionReservedSeconds = 200,
            status = "READY",
            providerSessionId = null,
            connectedAt = null,
            createdAt = createdAt,
            hardEndsAt = createdAt.plusSeconds(200),
            periodStartedAt = periodStartedAt,
            periodEndsAt = periodEndsAt,
        )
        connection.seedBoundary(
            "active-non-rtc",
            baseSeconds = 300,
            usedSeconds = 0,
            quotaReservedSeconds = 300,
            sessionReservedSeconds = 300,
            status = "ACTIVE",
            providerSessionId = "provider_non_rtc",
            connectedAt = createdAt,
            createdAt = createdAt,
            hardEndsAt = createdAt.plusSeconds(300),
            periodStartedAt = periodStartedAt,
            periodEndsAt = periodEndsAt,
        )
        connection.seedBoundary(
            "legacy-reservation-anchored-active",
            baseSeconds = 300,
            usedSeconds = 0,
            quotaReservedSeconds = 300,
            sessionReservedSeconds = 300,
            status = "ACTIVE",
            providerSessionId = "rtc_legacy_boundary",
            connectedAt = createdAt.plusSeconds(20),
            createdAt = createdAt,
            hardEndsAt = createdAt.plusSeconds(300),
            periodStartedAt = periodStartedAt,
            periodEndsAt = periodEndsAt,
        )
        connection.seedBoundary(
            "terminal-session",
            baseSeconds = 300,
            usedSeconds = 0,
            quotaReservedSeconds = 300,
            sessionReservedSeconds = 300,
            status = "COMPLETED",
            providerSessionId = "rtc_terminal",
            connectedAt = createdAt,
            createdAt = createdAt,
            hardEndsAt = createdAt.plusSeconds(300),
            periodStartedAt = periodStartedAt,
            periodEndsAt = periodEndsAt,
        )
    }

    private fun Connection.seedBoundary(
        suffix: String,
        baseSeconds: Int,
        usedSeconds: Int,
        quotaReservedSeconds: Int,
        sessionReservedSeconds: Int,
        status: String,
        providerSessionId: String?,
        connectedAt: Instant?,
        createdAt: Instant,
        hardEndsAt: Instant,
        periodStartedAt: Instant,
        periodEndsAt: Instant,
        limitOverrideSeconds: Int? = null,
    ) {
        val userId = insertUser(suffix, createdAt.minusSeconds(86_400))
        prepareStatement(
            """
            insert into user_voice_quota (
                user_id, tier_code, anchor_at, period_started_at, period_ends_at,
                limit_override_seconds, base_seconds, used_seconds, reserved_seconds,
                version, created_at, updated_at
            ) values (?, 'TIER2', ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setTimestamp(2, Timestamp.from(createdAt.minusSeconds(86_400)))
            statement.setTimestamp(3, Timestamp.from(periodStartedAt))
            statement.setTimestamp(4, Timestamp.from(periodEndsAt))
            if (limitOverrideSeconds == null) statement.setNull(5, Types.INTEGER)
            else statement.setInt(5, limitOverrideSeconds)
            statement.setInt(6, baseSeconds)
            statement.setInt(7, usedSeconds)
            statement.setInt(8, quotaReservedSeconds)
            statement.setTimestamp(9, Timestamp.from(createdAt))
            statement.setTimestamp(10, Timestamp.from(createdAt))
            statement.executeUpdate()
        }

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
                ?, ?, null, ?, ?,
                ?, 'PENDING', 'ko', 'gpt-realtime-test', 'marin', 'Migration fixture',
                5, ?, ?,
                ?, 0, 3600, ?,
                ?, ?, 0, null,
                null, null, null, null,
                null, ?, ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, suffix)
            statement.setLong(2, userId)
            statement.setString(3, "idempotency-$suffix")
            if (providerSessionId == null) statement.setNull(4, Types.VARCHAR)
            else statement.setString(4, providerSessionId)
            statement.setString(5, status)
            statement.setTimestamp(6, Timestamp.from(periodStartedAt))
            statement.setTimestamp(7, Timestamp.from(periodEndsAt))
            statement.setInt(8, sessionReservedSeconds)
            statement.setTimestamp(9, Timestamp.from(hardEndsAt))
            if (connectedAt == null) {
                statement.setNull(10, Types.TIMESTAMP)
                statement.setNull(11, Types.TIMESTAMP)
            } else {
                statement.setTimestamp(10, Timestamp.from(connectedAt))
                statement.setTimestamp(11, Timestamp.from(connectedAt))
            }
            statement.setTimestamp(12, Timestamp.from(createdAt))
            statement.setTimestamp(13, Timestamp.from(createdAt))
            statement.executeUpdate()
        }
    }

    private fun Connection.insertUser(suffix: String, createdAt: Instant): Long =
        prepareStatement(
            """
            insert into users (
                provider, provider_id, status, email, display_name, created_at, updated_at
            ) values ('EMAIL', ?, 'ACTIVE', ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, "voice-boundary-$suffix")
            statement.setString(2, "voice-boundary-$suffix@example.com")
            statement.setString(3, "Voice Boundary $suffix")
            statement.setTimestamp(4, Timestamp.from(createdAt))
            statement.setTimestamp(5, Timestamp.from(createdAt))
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Expected a generated user id." }
                keys.getLong(1)
            }
        }

    private fun Connection.boundary(sessionId: String): Boolean =
        createStatement().use { statement ->
            statement.executeQuery(
                "select monthly_quota_exhausts_at_hard_end from voice_tutor_sessions where id = '$sessionId'",
            ).use { result ->
                check(result.next()) { "Expected session $sessionId." }
                result.getBoolean(1)
            }
        }

    private fun Connection.nullBoundaryCount(): Int =
        createStatement().use { statement ->
            statement.executeQuery(
                "select count(*) from voice_tutor_sessions where monthly_quota_exhausts_at_hard_end is null",
            ).use { result ->
                check(result.next()) { "Expected a count." }
                result.getInt(1)
            }
        }
}
