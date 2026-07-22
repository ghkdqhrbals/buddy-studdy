package com.buddystudy.backend.scheduler.adapter.outbound.persistence

import com.buddystudy.backend.scheduler.application.model.*
import com.buddystudy.backend.scheduler.application.port.outbound.JobLockPort
import com.buddystudy.backend.scheduler.application.port.outbound.ScheduledJobRunPort
import com.buddystudy.backend.common.adapter.outbound.persistence.bindIndexed
import com.buddystudy.backend.common.adapter.outbound.persistence.indexedBindMarkers
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Repository
class ScheduledJobRunPersistenceAdapter(
    private val client: DatabaseClient,
) : ScheduledJobRunPort {
    override suspend fun isEnabled(jobName: String): Boolean =
        client.sql("select enabled from scheduled_jobs where job_name = :jobName").bind("jobName", jobName)
            .map { row, _ -> row.get("enabled", java.lang.Boolean::class.java)!!.booleanValue() }
            .one().awaitSingleOrNull() ?: true

    override suspend fun start(
        jobName: String,
        triggerType: JobTriggerType,
        retryOfRunId: Long?,
        createdBy: String,
    ): ScheduledJobRun {
        val id = client.sql(
            """
            insert into scheduled_job_runs
                (job_name, trigger_type, status, started_at, retry_of_run_id, created_by)
            values (:jobName, :triggerType, :status, :startedAt, :retryOfRunId, :createdBy)
            """.trimIndent(),
        ).bind("jobName", jobName).bind("triggerType", triggerType.name).bind("status", JobRunStatus.RUNNING.name)
            .bind("startedAt", Instant.now()).bindNullable("retryOfRunId", retryOfRunId, Long::class.javaObjectType)
            .bind("createdBy", createdBy)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        return findById(id)
    }

    override suspend fun finish(
        runId: Long,
        status: JobRunStatus,
        summary: String?,
        errorMessage: String?,
        durationMs: Long,
    ): ScheduledJobRun {
        client.sql(
            """
            update scheduled_job_runs set status = :status, finished_at = current_timestamp,
                duration_ms = :durationMs, summary = :summary, error_message = :errorMessage
            where id = :runId
            """.trimIndent(),
        ).bind("runId", runId).bind("status", status.name).bind("durationMs", durationMs)
            .bindNullable("summary", summary?.take(500), String::class.java)
            .bindNullable("errorMessage", errorMessage?.take(1000), String::class.java)
            .fetch().rowsUpdated().awaitSingle()
        return findById(runId)
    }

    override suspend fun findRuns(
        jobName: String?,
        runId: Long?,
        limit: Int,
        offset: Int,
    ): ScheduledJobRunPageResponse {
        val conditions = mutableListOf<String>()
        if (!jobName.isNullOrBlank()) conditions += "job_name = :jobName"
        if (runId != null) conditions += "id = :runId"
        val where = conditions.joinToString(prefix = if (conditions.isEmpty()) "" else " where ", separator = " and ")
        var countSpec = client.sql("select count(*) as total from scheduled_job_runs$where")
        var rowsSpec = client.sql("select * from scheduled_job_runs$where order by started_at desc, id desc limit :limit offset :offset")
            .bind("limit", limit).bind("offset", offset)
        if (!jobName.isNullOrBlank()) {
            countSpec = countSpec.bind("jobName", jobName.trim())
            rowsSpec = rowsSpec.bind("jobName", jobName.trim())
        }
        if (runId != null) {
            countSpec = countSpec.bind("runId", runId)
            rowsSpec = rowsSpec.bind("runId", runId)
        }
        val total = countSpec.map { row, _ -> row.get("total", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        val rows = rowsSpec.map { row, _ -> row.toRun() }.all().collectList().awaitSingle()
        return ScheduledJobRunPageResponse(rows, total, limit, offset)
    }

    override suspend fun findSnapshots(jobNames: List<String>): List<ScheduledJobSnapshot> {
        val jobFilter = if (jobNames.isEmpty()) "" else "where job_name in (${indexedBindMarkers("jobName", jobNames.size)})"
        var jobSpec = client.sql(
            """
            select job_name, enabled, schedule_type, schedule_value, timeout_seconds from scheduled_jobs
                $jobFilter order by job_name
            """.trimIndent(),
        )
        if (jobNames.isNotEmpty()) jobSpec = jobSpec.bindIndexed("jobName", jobNames)
        val snapshots = jobSpec.map { row, _ ->
            RawSnapshot(
                row.get("job_name", String::class.java)!!,
                row.get("enabled", java.lang.Boolean::class.java)!!.booleanValue(),
                row.get("schedule_type", String::class.java)!!,
                row.get("schedule_value", String::class.java)!!,
                row.get("timeout_seconds", Integer::class.java)!!.toInt().coerceAtLeast(1),
            )
        }.all().collectList().awaitSingle()
        if (snapshots.isEmpty()) return emptyList()
        val names = snapshots.map { it.jobName }
        val latest = latestRuns(names, null)
        val successful = latestRuns(names, JobRunStatus.SUCCESS)
        return snapshots.map {
            ScheduledJobSnapshot(
                it.jobName, it.enabled, it.scheduleType, it.scheduleValue, it.timeoutSeconds,
                latest[it.jobName], successful[it.jobName],
            )
        }
    }

    private suspend fun latestRuns(names: List<String>, status: JobRunStatus?): Map<String, ScheduledJobRun> {
        val statusClause = if (status == null) "" else "and status = :status"
        val jobMarkers = indexedBindMarkers("jobName", names.size)
        var spec = client.sql(
            """
            select * from (
                select r.*, row_number() over (partition by r.job_name order by r.started_at desc, r.id desc) rn
                from scheduled_job_runs r where r.job_name in ($jobMarkers) $statusClause
            ) ranked where rn = 1
            """.trimIndent(),
        ).bindIndexed("jobName", names)
        if (status != null) spec = spec.bind("status", status.name)
        return spec.map { row, _ -> row.toRun() }.all().collectList().awaitSingle().associateBy { it.jobName }
    }

    private suspend fun findById(id: Long): ScheduledJobRun =
        client.sql("select * from scheduled_job_runs where id = :id").bind("id", id)
            .map { row, _ -> row.toRun() }.one().awaitSingle()

    private fun Row.toRun() = ScheduledJobRun(
        id = get("id", java.lang.Long::class.java)!!.toLong(),
        jobName = get("job_name", String::class.java)!!,
        triggerType = JobTriggerType.valueOf(get("trigger_type", String::class.java)!!),
        status = JobRunStatus.valueOf(get("status", String::class.java)!!),
        startedAt = instant("started_at")!!,
        finishedAt = instant("finished_at"),
        durationMs = (get("duration_ms") as? Number)?.toLong(),
        summary = get("summary", String::class.java),
        errorMessage = get("error_message", String::class.java),
        retryOfRunId = (get("retry_of_run_id") as? Number)?.toLong(),
        createdBy = get("created_by", String::class.java)!!,
    )

    private fun Row.instant(column: String): Instant? =
        get(column)?.let { value ->
            when (value) {
                is Instant -> value
                is java.time.OffsetDateTime -> value.toInstant()
                is java.time.LocalDateTime -> value.toInstant(java.time.ZoneOffset.UTC)
                else -> error("Unsupported timestamp type for $column: ${value.javaClass.name}")
            }
        }

    private data class RawSnapshot(
        val jobName: String, val enabled: Boolean, val scheduleType: String,
        val scheduleValue: String, val timeoutSeconds: Int,
    )

    private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(name: String, value: T?, type: Class<T>) =
        if (value == null) bindNull(name, type) else bind(name, value)
}

@Repository
class PostgresAdvisoryJobLockAdapter(
    private val connectionFactory: ConnectionFactory,
) : JobLockPort {
    private val heldConnections = ConcurrentHashMap<String, Connection>()

    override suspend fun tryAcquire(jobName: String): Boolean {
        if (heldConnections.containsKey(jobName)) return false
        val connection = connectionFactory.create().awaitSingle()
        return try {
            val result = connection.createStatement("select pg_try_advisory_lock(hashtext($1)) as acquired")
                .bind(0, jobName).execute().awaitSingle()
            val acquired = result.map { row, _ -> row.get("acquired", java.lang.Boolean::class.java)!!.booleanValue() }
                .awaitSingle()
            if (acquired && heldConnections.putIfAbsent(jobName, connection) == null) true
            else {
                connection.close().awaitFirstOrNull()
                false
            }
        } catch (error: Exception) {
            connection.close().awaitFirstOrNull()
            throw error
        }
    }

    override suspend fun release(jobName: String) {
        val connection = heldConnections.remove(jobName) ?: return
        try {
            connection.createStatement("select pg_advisory_unlock(hashtext($1))")
                .bind(0, jobName).execute().awaitSingle()
        } finally {
            connection.close().awaitFirstOrNull()
        }
    }
}
