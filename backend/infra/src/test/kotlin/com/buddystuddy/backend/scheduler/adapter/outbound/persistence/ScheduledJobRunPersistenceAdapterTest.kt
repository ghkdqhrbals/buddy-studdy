package com.buddystuddy.backend.scheduler.adapter.outbound.persistence

import com.buddystuddy.backend.scheduler.application.model.JobRunStatus
import com.buddystuddy.backend.scheduler.application.model.JobTriggerType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource

class ScheduledJobRunPersistenceAdapterTest {
    private val dataSource = h2()
    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val adapter = ScheduledJobRunPersistenceAdapter(dataSource)

    @BeforeEach
    fun setUpSchema() {
        jdbc.jdbcTemplate.execute(
            """
            create table if not exists scheduled_jobs (
                job_name varchar(120) primary key,
                enabled boolean not null default true,
                schedule_type varchar(40) not null,
                schedule_value varchar(120) not null,
                max_retry_count integer not null default 3,
                timeout_seconds integer not null default 300,
                lock_seconds integer not null default 300,
                created_at timestamp not null default current_timestamp,
                updated_at timestamp not null default current_timestamp
            )
            """.trimIndent(),
        )
        jdbc.jdbcTemplate.execute(
            """
            create table if not exists scheduled_job_runs (
                id bigserial primary key,
                job_name varchar(120) not null,
                trigger_type varchar(40) not null,
                status varchar(40) not null,
                started_at timestamp not null,
                finished_at timestamp null,
                duration_ms bigint null,
                summary varchar(500) null,
                error_message varchar(1000) null,
                retry_of_run_id bigint null,
                created_by varchar(120) not null,
                created_at timestamp not null default current_timestamp
            )
            """.trimIndent(),
        )
        jdbc.jdbcTemplate.execute("delete from scheduled_job_runs")
        jdbc.jdbcTemplate.execute("delete from scheduled_jobs")
    }

    @Test
    fun `stores job run lifecycle`() {
        val started = adapter.start("admin-analytics-recent", JobTriggerType.SCHEDULED, null, "system")

        val finished = adapter.finish(started.id, JobRunStatus.SUCCESS, "rows=9", null, 17)

        assertThat(finished.status).isEqualTo(JobRunStatus.SUCCESS)
        assertThat(finished.summary).isEqualTo("rows=9")
        assertThat(adapter.findRuns("admin-analytics-recent", 10)).containsExactly(finished)
    }

    @Test
    fun `reads enabled flag from scheduled jobs`() {
        jdbc.update(
            """
            insert into scheduled_jobs (job_name, enabled, schedule_type, schedule_value, created_at, updated_at)
            values ('user-stats-refresh', false, 'CRON', '0 */5 * * * *', current_timestamp, current_timestamp)
            """.trimIndent(),
            emptyMap<String, Any>(),
        )

        assertThat(adapter.isEnabled("user-stats-refresh")).isFalse()
        assertThat(adapter.isEnabled("unknown-job")).isTrue()
    }

    private fun h2(): DataSource =
        DriverManagerDataSource().apply {
            setDriverClassName("org.h2.Driver")
            url = "jdbc:h2:mem:buddystuddy-scheduler-${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            username = "sa"
            password = ""
        }
}
