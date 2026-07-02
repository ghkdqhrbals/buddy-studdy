package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.common.adapter.inbound.web.dto.ReadinessCheckResponse
import com.buddystudy.backend.common.adapter.inbound.web.dto.ReadinessResponse
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

@Component
class ReadinessChecker(
    private val dataSource: DataSource,
    private val redisConnectionFactory: RedisConnectionFactory,
    private val properties: BuddyStudyProperties,
) {
    private val startedAt: Instant = Instant.now()
    private val jdbc = NamedParameterJdbcTemplate(dataSource)

    fun check(): ReadinessResponse {
        val checks = linkedMapOf<String, ReadinessCheckResponse>(
            "database" to checkDatabase(),
            "redis" to checkRedis(),
        )
        if (properties.monitoring.schedulerReadinessEnabled) {
            checks["scheduler"] = checkScheduler()
        }
        return ReadinessResponse(
            ok = checks.values.all { it.ok },
            checks = checks,
        )
    }

    private fun checkDatabase(): ReadinessCheckResponse =
        runCatching {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("select 1")
                }
            }
            ReadinessCheckResponse(ok = true)
        }.getOrElse { error ->
            ReadinessCheckResponse(ok = false, message = error.safeMessage())
        }

    private fun checkRedis(): ReadinessCheckResponse =
        runCatching {
            val connection = redisConnectionFactory.connection
            try {
                val pong = connection.ping()
                if (pong == null || !pong.equals("PONG", ignoreCase = true)) {
                    throw IllegalStateException("Unexpected Redis ping response: $pong")
                }
            } finally {
                connection.close()
            }
            ReadinessCheckResponse(ok = true)
        }.getOrElse { error ->
            ReadinessCheckResponse(ok = false, message = error.safeMessage())
        }

    private fun checkScheduler(): ReadinessCheckResponse {
        val monitoredJobs = properties.monitoring.schedulerMonitoredJobs
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (monitoredJobs.isEmpty()) return ReadinessCheckResponse(ok = true)

        val now = Instant.now()
        val startupGrace = Duration.ofMinutes(properties.monitoring.schedulerStartupGraceMinutes.coerceAtLeast(0))
        val staleThreshold = Duration.ofMinutes(properties.monitoring.schedulerStaleThresholdMinutes.coerceAtLeast(1))

        return runCatching {
            val params = MapSqlParameterSource()
                .addValue("jobNames", monitoredJobs)
            val rows = jdbc.query(
                """
                select j.job_name, j.enabled, max(r.started_at) as last_started_at
                from scheduled_jobs j
                left join scheduled_job_runs r on r.job_name = j.job_name
                where j.job_name in (:jobNames)
                group by j.job_name, j.enabled
                """.trimIndent(),
                params,
            ) { rs, _ ->
                SchedulerJobReadinessRow(
                    jobName = rs.getString("job_name"),
                    enabled = rs.getBoolean("enabled"),
                    lastStartedAt = rs.getTimestamp("last_started_at")?.toInstant(),
                )
            }.associateBy { it.jobName }

            val missingJobs = monitoredJobs.filterNot { rows.containsKey(it) }
            val staleJobs = rows.values
                .filter { it.enabled }
                .filter { row ->
                    row.lastStartedAt == null && Duration.between(startedAt, now) > startupGrace ||
                        row.lastStartedAt != null && Duration.between(row.lastStartedAt, now) > staleThreshold
                }
                .map { row ->
                    "${row.jobName} lastStartedAt=${row.lastStartedAt ?: "never"}"
                }

            when {
                missingJobs.isNotEmpty() -> ReadinessCheckResponse(
                    ok = false,
                    message = "Missing monitored scheduler jobs: ${missingJobs.joinToString(", ")}",
                )
                staleJobs.isNotEmpty() -> ReadinessCheckResponse(
                    ok = false,
                    message = "Stale scheduler jobs: ${staleJobs.joinToString("; ")}",
                )
                else -> ReadinessCheckResponse(ok = true)
            }
        }.getOrElse { error ->
            ReadinessCheckResponse(ok = false, message = error.safeMessage())
        }
    }

    private fun Throwable.safeMessage(): String =
        listOfNotNull(javaClass.simpleName, message?.take(200))
            .joinToString(": ")

    private data class SchedulerJobReadinessRow(
        val jobName: String,
        val enabled: Boolean,
        val lastStartedAt: Instant?,
    )
}
