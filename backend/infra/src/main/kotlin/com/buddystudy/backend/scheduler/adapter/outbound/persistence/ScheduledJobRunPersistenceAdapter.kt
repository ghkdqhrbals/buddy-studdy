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
        cursor: Long?,
    ): ScheduledJobRunPageResponse {
        val conditions = mutableListOf<String>()
        if (!jobName.isNullOrBlank()) conditions += "job_name = :jobName"
        if (runId != null) conditions += "id = :runId"
        if (runId == null && cursor != null) conditions += "id < :cursor"
        val where = conditions.joinToString(prefix = if (conditions.isEmpty()) "" else " where ", separator = " and ")
        var rowsSpec = client.sql("select * from scheduled_job_runs$where order by id desc limit :fetchLimit")
            .bind("fetchLimit", if (runId == null) limit + 1 else 1)
        if (!jobName.isNullOrBlank()) {
            rowsSpec = rowsSpec.bind("jobName", jobName.trim())
        }
        if (runId != null) {
            rowsSpec = rowsSpec.bind("runId", runId)
        }
        if (runId == null && cursor != null) {
            rowsSpec = rowsSpec.bind("cursor", cursor)
        }
        val fetched = rowsSpec.map { row, _ -> row.toRun() }
            .all()
            .collectList()
            .awaitSingle()
        val hasNext = runId == null && fetched.size > limit
        val rows = fetched.take(limit)
        return ScheduledJobRunPageResponse(
            runs = rows,
            limit = limit,
            nextCursor = if (hasNext) rows.lastOrNull()?.id else null,
            hasNext = hasNext,
        )
    }

    override suspend fun findSnapshotPage(limit: Int, offset: Int): ScheduledJobSnapshotPage {
        val total = client.sql("select count(*) as total from scheduled_jobs")
            .map { row, _ -> row.get("total", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()
        val snapshots = client.sql(
            """
            select job_name, enabled, schedule_type, schedule_value, timeout_seconds from scheduled_jobs
            order by job_name limit :limit offset :offset
            """.trimIndent(),
        ).bind("limit", limit).bind("offset", offset).map { row, _ ->
            RawSnapshot(
                row.get("job_name", String::class.java)!!,
                row.get("enabled", java.lang.Boolean::class.java)!!.booleanValue(),
                row.get("schedule_type", String::class.java)!!,
                row.get("schedule_value", String::class.java)!!,
                row.get("timeout_seconds", Integer::class.java)!!.toInt().coerceAtLeast(1),
            )
        }.all().collectList().awaitSingle()
        if (snapshots.isEmpty()) return ScheduledJobSnapshotPage(emptyList(), total, limit, offset)
        val names = snapshots.map { it.jobName }
        val latestIds = findLatestRunIds(names).associateBy { it.jobName }
        val runsById = findRunsById(
            latestIds.values.flatMapTo(mutableSetOf()) { ids ->
                listOfNotNull(ids.latestRunId, ids.lastSuccessfulRunId)
            },
        )
        val rows = snapshots.map {
            val ids = latestIds[it.jobName]
            ScheduledJobSnapshot(
                it.jobName, it.enabled, it.scheduleType, it.scheduleValue, it.timeoutSeconds,
                ids?.latestRunId?.let(runsById::get),
                ids?.lastSuccessfulRunId?.let(runsById::get),
            )
        }
        return ScheduledJobSnapshotPage(rows, total, limit, offset)
    }

    override suspend fun findExistingJobNames(jobNames: List<String>): Set<String> {
        if (jobNames.isEmpty()) return emptySet()
        val jobMarkers = indexedBindMarkers("jobName", jobNames.size)
        return client.sql("select job_name from scheduled_jobs where job_name in ($jobMarkers)")
            .bindIndexed("jobName", jobNames)
            .map { row, _ -> row.get("job_name", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .toSet()
    }

    private suspend fun findLatestRunIds(names: List<String>): List<LatestRunIds> {
        val indexedTopOneQueries = names.indices.joinToString("\nunion all\n") { index ->
            """
            select
                :jobName$index as job_name,
                (
                    select /*+ INDEX(candidate idx_scheduled_job_runs_name_started_id) */ candidate.id
                    from scheduled_job_runs candidate
                    where candidate.job_name = :jobName$index
                    order by candidate.started_at desc, candidate.id desc
                    limit 1
                ) as latest_run_id,
                (
                    select /*+ INDEX(candidate idx_scheduled_job_runs_name_status_started_id) */ candidate.id
                    from scheduled_job_runs candidate
                    where candidate.job_name = :jobName$index and candidate.status = :successStatus
                    order by candidate.started_at desc, candidate.id desc
                    limit 1
                ) as last_successful_run_id
            """.trimIndent()
        }
        return client.sql(indexedTopOneQueries)
            .bindIndexed("jobName", names)
            .bind("successStatus", JobRunStatus.SUCCESS.name)
            .map { row, _ ->
                LatestRunIds(
                    jobName = row.get("job_name", String::class.java)!!,
                    latestRunId = (row.get("latest_run_id") as? Number)?.toLong(),
                    lastSuccessfulRunId = (row.get("last_successful_run_id") as? Number)?.toLong(),
                )
            }
            .all()
            .collectList()
            .awaitSingle()
    }

    private suspend fun findRunsById(runIds: Set<Long>): Map<Long, ScheduledJobRun> {
        if (runIds.isEmpty()) return emptyMap()
        val runMarkers = indexedBindMarkers("runId", runIds.size)
        return client.sql("select * from scheduled_job_runs where id in ($runMarkers)")
            .bindIndexed("runId", runIds)
            .map { row, _ -> row.toRun() }
            .all()
            .collectList()
            .awaitSingle()
            .associateBy(ScheduledJobRun::id)
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

    private data class LatestRunIds(
        val jobName: String,
        val latestRunId: Long?,
        val lastSuccessfulRunId: Long?,
    )

    private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(name: String, value: T?, type: Class<T>) =
        if (value == null) bindNull(name, type) else bind(name, value)
}

@Repository
class MySqlAdvisoryJobLockAdapter(
    private val connectionFactory: ConnectionFactory,
) : JobLockPort {
    private val heldConnections = ConcurrentHashMap<String, Connection>()

    override suspend fun tryAcquire(jobName: String): Boolean {
        if (heldConnections.containsKey(jobName)) return false
        val connection = connectionFactory.create().awaitSingle()
        return try {
            val result = connection.createStatement("select get_lock(?, 0) as acquired")
                .bind(0, jobName).execute().awaitSingle()
            val acquired = result.map { row, _ -> (row.get("acquired") as Number).toInt() == 1 }
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
            val result = connection.createStatement("select release_lock(?) as released")
                .bind(0, jobName)
                .execute()
                .awaitSingle()
            val released = result
                .map { row, _ -> (row.get("released") as? Number)?.toInt() == 1 }
                .awaitSingle()
            check(released) { "MySQL advisory lock was not held for job: $jobName" }
        } finally {
            connection.close().awaitFirstOrNull()
        }
    }
}
