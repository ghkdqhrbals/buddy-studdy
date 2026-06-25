package com.buddystuddy.backend.scheduler.adapter.outbound.persistence

import com.buddystuddy.backend.scheduler.application.model.JobRunStatus
import com.buddystuddy.backend.scheduler.application.model.JobTriggerType
import com.buddystuddy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystuddy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystuddy.backend.scheduler.application.port.outbound.JobLockPort
import com.buddystuddy.backend.scheduler.application.port.outbound.ScheduledJobRunPort
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

    override fun findRuns(jobName: String?, limit: Int, offset: Int): ScheduledJobRunPageResponse {
        val whereSql = if (jobName != null) "\nwhere job_name = :jobName" else ""
        val params = MapSqlParameterSource()
            .addValue("limit", limit)
            .addValue("offset", offset)
            .apply {
                if (jobName != null) {
                    addValue("jobName", jobName)
                }
            }
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
