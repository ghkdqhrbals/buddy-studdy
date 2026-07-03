package com.buddystudy.backend.scheduler.adapter.outbound.persistence

import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystudy.backend.scheduler.application.model.ScheduledJobSnapshot
import com.buddystudy.backend.scheduler.application.port.outbound.JobLockPort
import com.buddystudy.backend.scheduler.application.port.outbound.ScheduledJobRunPort
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

@Repository
class ScheduledJobRunPersistenceAdapter(
    @Qualifier("dataSource") dataSource: DataSource,
) : ScheduledJobRunPort {
    private val jdbc = NamedParameterJdbcTemplate(dataSource)

    override fun isEnabled(jobName: String): Boolean =
        jdbc.query(
            "select enabled from scheduled_jobs where job_name = :jobName",
            mapOf("jobName" to jobName),
        ) { rs, _ -> rs.getBoolean("enabled") }.firstOrNull() ?: true

    override fun start(jobName: String, triggerType: JobTriggerType, retryOfRunId: Long?, createdBy: String): ScheduledJobRun {
        val keyHolder = GeneratedKeyHolder()
        val params = MapSqlParameterSource()
            .addValue("jobName", jobName)
            .addValue("triggerType", triggerType.name)
            .addValue("status", JobRunStatus.RUNNING.name)
            .addValue("startedAt", Timestamp.from(java.time.Instant.now()))
            .addValue("retryOfRunId", retryOfRunId)
            .addValue("createdBy", createdBy)
        jdbc.update(
            """
            insert into scheduled_job_runs (
                job_name, trigger_type, status, started_at, retry_of_run_id, created_by
            ) values (
                :jobName, :triggerType, :status, :startedAt, :retryOfRunId, :createdBy
            )
            """.trimIndent(),
            params,
            keyHolder,
            arrayOf("id"),
        )
        return findById(keyHolder.key!!.toLong())
    }

    override fun finish(runId: Long, status: JobRunStatus, summary: String?, errorMessage: String?, durationMs: Long): ScheduledJobRun {
        jdbc.update(
            """
            update scheduled_job_runs
            set status = :status,
                finished_at = current_timestamp,
                duration_ms = :durationMs,
                summary = :summary,
                error_message = :errorMessage
            where id = :runId
            """.trimIndent(),
            mapOf(
                "runId" to runId,
                "status" to status.name,
                "durationMs" to durationMs,
                "summary" to summary?.take(500),
                "errorMessage" to errorMessage?.take(1000),
            ),
        )
        return findById(runId)
    }

    override fun findRuns(jobName: String?, runId: Long?, limit: Int, offset: Int): ScheduledJobRunPageResponse {
        val normalizedJobName = jobName?.trim()?.takeIf { it.isNotEmpty() }
        val conditions = mutableListOf<String>()
        val params = MapSqlParameterSource()
            .addValue("limit", limit)
            .addValue("offset", offset)
        if (normalizedJobName != null) {
            conditions += "job_name = :jobName"
            params.addValue("jobName", normalizedJobName)
        }
        if (runId != null) {
            conditions += "id = :runId"
            params.addValue("runId", runId)
        }
        val whereSql = conditions.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "\nwhere ", separator = " and ")
            .orEmpty()
        val total = jdbc.queryForObject(
            "select count(*) from scheduled_job_runs$whereSql",
            params,
            Long::class.java,
        ) ?: 0L
        val sql = buildString {
            append(
                """
                select *
                from scheduled_job_runs
                """.trimIndent(),
            )
            append(whereSql)
            append("\norder by started_at desc, id desc\nlimit :limit offset :offset")
        }
        val rows = jdbc.query(sql, params) { rs, _ -> rs.toRun() }
        return ScheduledJobRunPageResponse(rows, total, limit, offset)
    }

    override fun findSnapshots(jobNames: List<String>): List<ScheduledJobSnapshot> {
        val params = MapSqlParameterSource()
        val whereSql = if (jobNames.isEmpty()) {
            ""
        } else {
            params.addValue("jobNames", jobNames)
            "where j.job_name in (:jobNames)"
        }
        val snapshots = jdbc.query(
            """
            select j.job_name, j.enabled, j.schedule_type, j.schedule_value, j.timeout_seconds
            from scheduled_jobs j
            $whereSql
            order by j.job_name
            """.trimIndent(),
            params,
        ) { rs, _ ->
            RawScheduledJobSnapshot(
                jobName = rs.getString("job_name"),
                enabled = rs.getBoolean("enabled"),
                scheduleType = rs.getString("schedule_type"),
                scheduleValue = rs.getString("schedule_value"),
                timeoutSeconds = rs.getInt("timeout_seconds").coerceAtLeast(1),
            )
        }
        if (snapshots.isEmpty()) return emptyList()

        val latestRuns = jdbc.query(
            """
            select *
            from (
                select r.*,
                       row_number() over (partition by r.job_name order by r.started_at desc, r.id desc) as rn
                from scheduled_job_runs r
                where r.job_name in (:jobNames)
            ) latest_runs
            where rn = 1
            """.trimIndent(),
            MapSqlParameterSource("jobNames", snapshots.map { it.jobName }),
        ) { rs, _ -> rs.toRun() }.associateBy { it.jobName }

        val lastSuccessfulRuns = jdbc.query(
            """
            select *
            from (
                select r.*,
                       row_number() over (partition by r.job_name order by r.started_at desc, r.id desc) as rn
                from scheduled_job_runs r
                where r.job_name in (:jobNames)
                  and r.status = :successStatus
            ) successful_runs
            where rn = 1
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("jobNames", snapshots.map { it.jobName })
                .addValue("successStatus", JobRunStatus.SUCCESS.name),
        ) { rs, _ -> rs.toRun() }.associateBy { it.jobName }

        return snapshots.map {
            ScheduledJobSnapshot(
                jobName = it.jobName,
                enabled = it.enabled,
                scheduleType = it.scheduleType,
                scheduleValue = it.scheduleValue,
                timeoutSeconds = it.timeoutSeconds,
                latestRun = latestRuns[it.jobName],
                lastSuccessfulRun = lastSuccessfulRuns[it.jobName],
            )
        }
    }

    private fun findById(id: Long): ScheduledJobRun =
        jdbc.query(
            "select * from scheduled_job_runs where id = :id",
            mapOf("id" to id),
        ) { rs, _ -> rs.toRun() }.single()

    private fun ResultSet.toRun(): ScheduledJobRun =
        ScheduledJobRun(
            id = getLong("id"),
            jobName = getString("job_name"),
            triggerType = JobTriggerType.valueOf(getString("trigger_type")),
            status = JobRunStatus.valueOf(getString("status")),
            startedAt = getTimestamp("started_at").toInstant(),
            finishedAt = getTimestamp("finished_at")?.toInstant(),
            durationMs = getObject("duration_ms")?.let { (it as Number).toLong() },
            summary = getString("summary"),
            errorMessage = getString("error_message"),
            retryOfRunId = getObject("retry_of_run_id")?.let { (it as Number).toLong() },
            createdBy = getString("created_by"),
        )

    private data class RawScheduledJobSnapshot(
        val jobName: String,
        val enabled: Boolean,
        val scheduleType: String,
        val scheduleValue: String,
        val timeoutSeconds: Int,
    )
}

@Repository
class PostgresAdvisoryJobLockAdapter(
    @Qualifier("dataSource") dataSource: DataSource,
) : JobLockPort {
    private val jdbc = NamedParameterJdbcTemplate(dataSource)

    override fun tryAcquire(jobName: String): Boolean =
        jdbc.queryForObject(
            "select pg_try_advisory_lock(hashtext(:jobName))",
            mapOf("jobName" to jobName),
            Boolean::class.java,
        ) ?: false

    override fun release(jobName: String) {
        jdbc.queryForObject(
            "select pg_advisory_unlock(hashtext(:jobName))",
            mapOf("jobName" to jobName),
            Boolean::class.java,
        )
    }
}
