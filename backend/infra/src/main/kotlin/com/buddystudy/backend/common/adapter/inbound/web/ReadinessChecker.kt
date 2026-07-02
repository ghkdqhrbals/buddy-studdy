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
            checkedAt = Instant.now(),
            service = properties.monitoring.serviceName,
            environment = properties.monitoring.environmentName,
            checks = checks,
        )
    }

    private fun checkDatabase(): ReadinessCheckResponse =
        timedCheck {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("select 1")
                }
            }
            ReadinessCheckResponse(ok = true)
        }

    private fun checkRedis(): ReadinessCheckResponse =
        timedCheck {
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

        return timedCheck {
            val params = MapSqlParameterSource()
                .addValue("jobNames", monitoredJobs)
            val rows = jdbc.query(
                """
                select
                    j.job_name,
                    j.enabled,
                    max(r.started_at) as latest_started_at,
                    max(case when r.status = 'SUCCESS' then r.started_at end) as last_successful_started_at
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
                    latestStartedAt = rs.getTimestamp("latest_started_at")?.toInstant(),
                    lastSuccessfulStartedAt = rs.getTimestamp("last_successful_started_at")?.toInstant(),
                )
            }.associateBy { it.jobName }

            val missingJobs = monitoredJobs.filterNot { rows.containsKey(it) }
            val staleJobDetails = rows.values
                .filter { it.enabled }
                .mapNotNull { row ->
                    val successfulStartedAt = row.lastSuccessfulStartedAt
                    val staleFor = Duration.between(successfulStartedAt ?: startedAt, now)
                    val stale = successfulStartedAt == null && Duration.between(startedAt, now) > startupGrace ||
                        successfulStartedAt != null && staleFor > staleThreshold
                    if (!stale) return@mapNotNull null
                    mapOf(
                        "jobName" to row.jobName,
                        "latestStartedAt" to row.latestStartedAt?.toString(),
                        "lastSuccessfulStartedAt" to successfulStartedAt?.toString(),
                        "staleForSeconds" to staleFor.seconds.coerceAtLeast(0),
                    )
                }
            val staleJobMessages = staleJobDetails.map { detail ->
                "${detail["jobName"]} lastSuccessfulStartedAt=${detail["lastSuccessfulStartedAt"] ?: "never"}"
            }

            fun schedulerDetails(vararg extra: Pair<String, Any?>): Map<String, Any?> =
                mapOf(
                    "monitoredJobs" to monitoredJobs,
                    "thresholdSeconds" to staleThreshold.seconds,
                    "startupGraceSeconds" to startupGrace.seconds,
                ) + extra.toMap()

            when {
                missingJobs.isNotEmpty() -> ReadinessCheckResponse(
                    ok = false,
                    message = "Missing monitored scheduler jobs: ${missingJobs.joinToString(", ")}",
                    details = schedulerDetails("missingJobs" to missingJobs),
                )
                staleJobDetails.isNotEmpty() -> ReadinessCheckResponse(
                    ok = false,
                    message = "Stale scheduler jobs: ${staleJobMessages.joinToString("; ")}",
                    details = schedulerDetails("staleJobs" to staleJobDetails),
                )
                else -> ReadinessCheckResponse(
                    ok = true,
                    details = schedulerDetails(),
                )
            }
        }
    }

    private fun timedCheck(block: () -> ReadinessCheckResponse): ReadinessCheckResponse {
        val started = System.nanoTime()
        val response = runCatching(block).getOrElse { error ->
            ReadinessCheckResponse(ok = false, message = error.safeMessage())
        }
        return response.copy(durationMs = elapsedMs(started))
    }

    private fun elapsedMs(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)

    private fun Throwable.safeMessage(): String =
        listOfNotNull(javaClass.simpleName, message?.take(200))
            .joinToString(": ")

    private data class SchedulerJobReadinessRow(
        val jobName: String,
        val enabled: Boolean,
        val latestStartedAt: Instant?,
        val lastSuccessfulStartedAt: Instant?,
    )
}
