package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.common.adapter.outbound.persistence.bindIndexed
import com.buddystudy.backend.common.adapter.outbound.persistence.indexedBindMarkers
import com.buddystudy.backend.common.adapter.inbound.web.dto.ReadinessCheckResponse
import com.buddystudy.backend.common.adapter.inbound.web.dto.ReadinessResponse
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Component
class ReadinessChecker(
    private val databaseClient: DatabaseClient,
    private val redisConnectionFactory: ReactiveRedisConnectionFactory,
    private val properties: BuddyStudyProperties,
) {
    private val startedAt: Instant = Instant.now()

    suspend fun check(includeScheduler: Boolean = true): ReadinessResponse {
        val checks = linkedMapOf<String, ReadinessCheckResponse>(
            "database" to checkDatabase(),
            "redis" to checkRedis(),
        )
        if (includeScheduler && properties.monitoring.schedulerReadinessEnabled) {
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

    private suspend fun checkDatabase(): ReadinessCheckResponse =
        timedCheck {
            databaseClient.sql("select 1").fetch().rowsUpdated().awaitSingle()
            ReadinessCheckResponse(ok = true)
        }

    private suspend fun checkRedis(): ReadinessCheckResponse =
        timedCheck {
            val connection = redisConnectionFactory.reactiveConnection
            try {
                val pong = connection.ping().awaitSingle()
                if (!pong.equals("PONG", ignoreCase = true)) {
                    throw IllegalStateException("Unexpected Redis ping response: $pong")
                }
            } finally {
                connection.closeLater().awaitFirstOrNull()
            }
            ReadinessCheckResponse(ok = true)
        }

    private suspend fun checkScheduler(): ReadinessCheckResponse {
        val monitoredJobs = properties.monitoring.schedulerMonitoredJobs
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (monitoredJobs.isEmpty()) return ReadinessCheckResponse(ok = true)

        val now = Instant.now()
        val startupGrace = Duration.ofMinutes(properties.monitoring.schedulerStartupGraceMinutes.coerceAtLeast(0))
        val staleThreshold = Duration.ofMinutes(properties.monitoring.schedulerStaleThresholdMinutes.coerceAtLeast(1))

        return timedCheck {
            val jobMarkers = indexedBindMarkers("jobName", monitoredJobs.size)
            val rows = databaseClient.sql(
                """
                select
                    j.job_name,
                    j.enabled,
                    j.timeout_seconds,
                    max(r.started_at) as latest_started_at,
                    max(case when r.status = 'SUCCESS' then r.started_at end) as last_successful_started_at,
                    (
                        select r2.id
                        from scheduled_job_runs r2
                        where r2.job_name = j.job_name
                        order by r2.started_at desc, r2.id desc
                        limit 1
                    ) as latest_run_id,
                    (
                        select r2.status
                        from scheduled_job_runs r2
                        where r2.job_name = j.job_name
                        order by r2.started_at desc, r2.id desc
                        limit 1
                    ) as latest_status,
                    (
                        select r2.error_message
                        from scheduled_job_runs r2
                        where r2.job_name = j.job_name
                        order by r2.started_at desc, r2.id desc
                        limit 1
                    ) as latest_error_message
                from scheduled_jobs j
                left join scheduled_job_runs r on r.job_name = j.job_name
                where j.job_name in ($jobMarkers)
                group by j.job_name, j.enabled, j.timeout_seconds
                """.trimIndent(),
            )
                .bindIndexed("jobName", monitoredJobs)
                .map { row, _ -> row.toSchedulerJobReadinessRow() }
                .all()
                .collectList()
                .awaitSingle()
                .associateBy { it.jobName }

            val missingJobs = monitoredJobs.filterNot { rows.containsKey(it) }
            val disabledJobs = rows.values
                .filter { !it.enabled }
                .map { it.jobName }
            val failedJobDetails = rows.values
                .filter { it.enabled && it.latestStatus == "FAILED" }
                .map { row ->
                    mapOf(
                        "jobName" to row.jobName,
                        "latestRunId" to row.latestRunId,
                        "latestStartedAt" to row.latestStartedAt?.toString(),
                        "latestStatus" to row.latestStatus,
                        "latestErrorMessage" to row.latestErrorMessage,
                    )
                }
            val stuckJobDetails = rows.values
                .filter { row ->
                    row.enabled &&
                        row.latestStatus == "RUNNING" &&
                        row.latestStartedAt != null &&
                        Duration.between(row.latestStartedAt, now).seconds > row.timeoutSeconds
                }
                .map { row ->
                    mapOf(
                        "jobName" to row.jobName,
                        "latestRunId" to row.latestRunId,
                        "latestStartedAt" to row.latestStartedAt?.toString(),
                        "latestStatus" to row.latestStatus,
                        "timeoutSeconds" to row.timeoutSeconds,
                        "runningForSeconds" to Duration.between(row.latestStartedAt, now).seconds.coerceAtLeast(0),
                    )
                }
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
                        "latestRunId" to row.latestRunId,
                        "latestStartedAt" to row.latestStartedAt?.toString(),
                        "latestStatus" to row.latestStatus,
                        "latestErrorMessage" to row.latestErrorMessage,
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
                disabledJobs.isNotEmpty() -> ReadinessCheckResponse(
                    ok = false,
                    message = "Disabled scheduler jobs: ${disabledJobs.joinToString(", ")}",
                    details = schedulerDetails("disabledJobs" to disabledJobs),
                )
                failedJobDetails.isNotEmpty() -> ReadinessCheckResponse(
                    ok = false,
                    message = "Failed scheduler jobs: ${failedJobDetails.joinToString("; ") { "${it["jobName"]} error=${it["latestErrorMessage"] ?: "unknown"}" }}",
                    details = schedulerDetails("failedJobs" to failedJobDetails),
                )
                stuckJobDetails.isNotEmpty() -> ReadinessCheckResponse(
                    ok = false,
                    message = "Stuck scheduler jobs: ${stuckJobDetails.joinToString("; ") { "${it["jobName"]} runningFor=${it["runningForSeconds"]}s timeout=${it["timeoutSeconds"]}s" }}",
                    details = schedulerDetails("stuckJobs" to stuckJobDetails),
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

    private suspend fun timedCheck(block: suspend () -> ReadinessCheckResponse): ReadinessCheckResponse {
        val started = System.nanoTime()
        val response = try {
            block()
        } catch (error: Throwable) {
            ReadinessCheckResponse(ok = false, message = error.safeMessage())
        }
        return response.copy(durationMs = elapsedMs(started))
    }

    private fun elapsedMs(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)

    private fun Throwable.safeMessage(): String =
        listOfNotNull(javaClass.simpleName, message?.take(200))
            .joinToString(": ")

    private fun Row.toSchedulerJobReadinessRow() = SchedulerJobReadinessRow(
        jobName = get("job_name", String::class.java)!!,
        enabled = get("enabled", java.lang.Boolean::class.java)?.booleanValue() ?: false,
        timeoutSeconds = (get("timeout_seconds", java.lang.Integer::class.java)?.toInt() ?: 1).coerceAtLeast(1),
        latestStartedAt = instant("latest_started_at"),
        lastSuccessfulStartedAt = instant("last_successful_started_at"),
        latestRunId = get("latest_run_id", java.lang.Long::class.java)?.toLong(),
        latestStatus = get("latest_status", String::class.java),
        latestErrorMessage = get("latest_error_message", String::class.java),
    )

    private fun Row.instant(column: String): Instant? =
        get(column)?.let { value ->
            when (value) {
                is Instant -> value
                is OffsetDateTime -> value.toInstant()
                is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
                else -> error("Unsupported timestamp type for $column: ${value.javaClass.name}")
            }
        }

    private data class SchedulerJobReadinessRow(
        val jobName: String,
        val enabled: Boolean,
        val timeoutSeconds: Int,
        val latestStartedAt: Instant?,
        val lastSuccessfulStartedAt: Instant?,
        val latestRunId: Long?,
        val latestStatus: String?,
        val latestErrorMessage: String?,
    )
}
