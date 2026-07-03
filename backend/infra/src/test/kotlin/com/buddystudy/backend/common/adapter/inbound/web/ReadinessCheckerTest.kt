package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.config.BuddyStudyProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.logging.Logger
import javax.sql.DataSource

class ReadinessCheckerTest {
    @Test
    fun `readiness is ok when database and redis are reachable`() {
        val checker = ReadinessChecker(h2DataSource(), redisFactory("PONG"), BuddyStudyProperties())

        val response = checker.check()

        assertThat(response.ok).isTrue()
        assertThat(response.checkedAt).isNotNull()
        assertThat(response.service).isEqualTo("BuddyStudy backend")
        assertThat(response.environment).isEqualTo("production")
        assertThat(response.checks["database"]?.ok).isTrue()
        assertThat(response.checks["redis"]?.ok).isTrue()
        assertThat(response.checks["scheduler"]?.ok).isTrue()
        assertThat(response.checks.values).allSatisfy { check ->
            assertThat(check.durationMs).isGreaterThanOrEqualTo(0L)
        }
    }

    @Test
    fun `readiness response uses configured monitoring identity`() {
        val checker = ReadinessChecker(
            h2DataSource(),
            redisFactory("PONG"),
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(
                    serviceName = "BuddyStudy API",
                    environmentName = "dev",
                ),
            ),
        )

        val response = checker.check()

        assertThat(response.service).isEqualTo("BuddyStudy API")
        assertThat(response.environment).isEqualTo("dev")
    }

    @Test
    fun `readiness fails when database is unavailable`() {
        val checker = ReadinessChecker(failingDataSource(), redisFactory("PONG"), BuddyStudyProperties())

        val response = checker.check()

        assertThat(response.ok).isFalse()
        assertThat(response.checks["database"]?.ok).isFalse()
        assertThat(response.checks["database"]?.message).contains("SQLException")
        assertThat(response.checks["redis"]?.ok).isTrue()
    }

    @Test
    fun `readiness fails when redis ping is unavailable`() {
        val checker = ReadinessChecker(h2DataSource(), redisFactory(error = IllegalStateException("redis down")), BuddyStudyProperties())

        val response = checker.check()

        assertThat(response.ok).isFalse()
        assertThat(response.checks["database"]?.ok).isTrue()
        assertThat(response.checks["redis"]?.ok).isFalse()
        assertThat(response.checks["redis"]?.message).contains("redis down")
    }

    @Test
    fun `readiness fails when redis stream coordinator is unavailable`() {
        val checker = ReadinessChecker(
            h2DataSource(),
            redisFactory("PONG"),
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(
                    coordinatorReadinessEnabled = true,
                    coordinatorBaseUrl = "http://coordinator.test",
                ),
            ),
            coordinatorHealthClient = CoordinatorHealthClient { _, _ -> throw IllegalStateException("coordinator down") },
        )

        val response = checker.check()

        assertThat(response.ok).isFalse()
        assertThat(response.checks["redisStreamCoordinator"]?.ok).isFalse()
        assertThat(response.checks["redisStreamCoordinator"]?.message).contains("coordinator down")
    }

    @Test
    fun `dependency readiness excludes redis stream coordinator for Kubernetes probes`() {
        val checker = ReadinessChecker(
            h2DataSource(),
            redisFactory("PONG"),
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(
                    coordinatorReadinessEnabled = true,
                    coordinatorBaseUrl = "http://coordinator.test",
                ),
            ),
            coordinatorHealthClient = CoordinatorHealthClient { _, _ -> throw IllegalStateException("coordinator down") },
        )

        val response = checker.check(includeScheduler = false)

        assertThat(response.ok).isTrue()
        assertThat(response.checks).containsKeys("database", "redis")
        assertThat(response.checks).doesNotContainKey("redisStreamCoordinator")
    }

    @Test
    fun `readiness fails when monitored scheduler jobs are stale`() {
        val dataSource = h2DataSource(lastStartedAt = Instant.now().minusSeconds(60 * 60), seedJobs = true)
        val checker = ReadinessChecker(
            dataSource,
            redisFactory("PONG"),
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(
                    schedulerStaleThresholdMinutes = 15,
                ),
            ),
        )

        val response = checker.check()

        assertThat(response.ok).isFalse()
        assertThat(response.checks["scheduler"]?.ok).isFalse()
        assertThat(response.checks["scheduler"]?.message).contains("Stale scheduler jobs")
        assertThat(response.checks["scheduler"]?.message).contains("question-schedule")
        assertThat(response.checks["scheduler"]?.details?.get("staleJobs").toString()).contains("question-schedule")
        assertThat(response.checks["scheduler"]?.details?.get("thresholdSeconds")).isEqualTo(900L)
    }

    @Test
    fun `readiness can exclude scheduler checks for Kubernetes probes`() {
        val dataSource = h2DataSource(lastStartedAt = Instant.now().minusSeconds(60 * 60), seedJobs = true)
        val checker = ReadinessChecker(
            dataSource,
            redisFactory("PONG"),
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(
                    schedulerStaleThresholdMinutes = 15,
                ),
            ),
        )

        val response = checker.check(includeScheduler = false)

        assertThat(response.ok).isTrue()
        assertThat(response.checks).containsKeys("database", "redis")
        assertThat(response.checks).doesNotContainKey("scheduler")
    }

    @Test
    fun `readiness does not fail new scheduler jobs during startup grace`() {
        val dataSource = h2DataSource(lastStartedAt = null, seedJobs = true)
        val checker = ReadinessChecker(
            dataSource,
            redisFactory("PONG"),
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(
                    schedulerStartupGraceMinutes = 10,
                    schedulerStaleThresholdMinutes = 1,
                ),
            ),
        )

        val response = checker.check()

        assertThat(response.ok).isTrue()
        assertThat(response.checks["scheduler"]?.ok).isTrue()
    }

    @Test
    fun `readiness omits scheduler check when scheduler readiness is disabled`() {
        val dataSource = h2DataSource(lastStartedAt = Instant.now().minusSeconds(60 * 60), seedJobs = true)
        val checker = ReadinessChecker(
            dataSource,
            redisFactory("PONG"),
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(
                    schedulerReadinessEnabled = false,
                    schedulerStaleThresholdMinutes = 1,
                ),
            ),
        )

        val response = checker.check()

        assertThat(response.ok).isTrue()
        assertThat(response.checks).containsKeys("database", "redis")
        assertThat(response.checks).doesNotContainKey("scheduler")
    }

    @Test
    fun `readiness fails when monitored scheduler job seed is missing`() {
        val dataSource = h2DataSource(lastStartedAt = null, seedJobs = false)
        val checker = ReadinessChecker(dataSource, redisFactory("PONG"), BuddyStudyProperties())

        val response = checker.check()

        assertThat(response.ok).isFalse()
        assertThat(response.checks["scheduler"]?.ok).isFalse()
        assertThat(response.checks["scheduler"]?.message).contains("Missing monitored scheduler jobs")
        assertThat(response.checks["scheduler"]?.details?.get("missingJobs")).isEqualTo(
            listOf(
                "question-schedule",
                "question-push-outbox-dispatch",
                "user-stats-refresh",
                "admin-analytics-recent",
                "admin-analytics-correction",
            ),
        )
    }

    @Test
    fun `readiness fails when monitored scheduler jobs are disabled`() {
        val dataSource = h2DataSource(lastStartedAt = Instant.now().minusSeconds(60 * 60), seedJobs = true)
        JdbcTemplate(dataSource).update("update scheduled_jobs set enabled = false where job_name = ?", "question-schedule")
        val checker = ReadinessChecker(
            dataSource,
            redisFactory("PONG"),
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(
                    schedulerStaleThresholdMinutes = 15,
                    schedulerMonitoredJobs = listOf("question-schedule"),
                ),
            ),
        )

        val response = checker.check()

        assertThat(response.ok).isFalse()
        assertThat(response.checks["scheduler"]?.ok).isFalse()
        assertThat(response.checks["scheduler"]?.message).contains("Disabled scheduler jobs")
        assertThat(response.checks["scheduler"]?.details?.get("disabledJobs")).isEqualTo(listOf("question-schedule"))
    }

    @Test
    fun `readiness prioritizes latest failed scheduler run over stale successful run`() {
        val dataSource = h2DataSource(lastStartedAt = Instant.now().minusSeconds(60 * 60), seedJobs = true)
        JdbcTemplate(dataSource).update(
            "insert into scheduled_job_runs (job_name, trigger_type, status, started_at, created_by) values (?, 'SCHEDULED', 'FAILED', ?, 'system')",
            "question-schedule",
            Timestamp.from(Instant.now()),
        )
        val checker = ReadinessChecker(
            dataSource,
            redisFactory("PONG"),
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(
                    schedulerStaleThresholdMinutes = 15,
                    schedulerMonitoredJobs = listOf("question-schedule"),
                ),
            ),
        )

        val response = checker.check()

        assertThat(response.ok).isFalse()
        assertThat(response.checks["scheduler"]?.ok).isFalse()
        assertThat(response.checks["scheduler"]?.message).contains("Failed scheduler jobs")
        assertThat(response.checks["scheduler"]?.details?.get("failedJobs").toString()).contains("question-schedule")
    }

    @Test
    fun `readiness fails when latest scheduler run failed even if last successful run is recent`() {
        val dataSource = h2DataSource(lastStartedAt = Instant.now(), seedJobs = true)
        JdbcTemplate(dataSource).update(
            """
            insert into scheduled_job_runs (job_name, trigger_type, status, started_at, error_message, created_by)
            values (?, 'SCHEDULED', 'FAILED', ?, 'OpenAI timeout', 'system')
            """.trimIndent(),
            "question-schedule",
            Timestamp.from(Instant.now()),
        )
        val checker = ReadinessChecker(
            dataSource,
            redisFactory("PONG"),
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(
                    schedulerStaleThresholdMinutes = 15,
                    schedulerMonitoredJobs = listOf("question-schedule"),
                ),
            ),
        )

        val response = checker.check()

        assertThat(response.ok).isFalse()
        assertThat(response.checks["scheduler"]?.ok).isFalse()
        assertThat(response.checks["scheduler"]?.message).contains("Failed scheduler jobs")
        assertThat(response.checks["scheduler"]?.details?.get("failedJobs").toString()).contains("OpenAI timeout")
        assertThat(response.checks["scheduler"]?.details?.get("failedJobs").toString()).contains("latestRunId")
    }

    @Test
    fun `readiness fails when latest scheduler run is stuck running past timeout`() {
        val dataSource = h2DataSource(lastStartedAt = Instant.now().minusSeconds(1_200), seedJobs = true)
        JdbcTemplate(dataSource).update(
            """
            insert into scheduled_job_runs (job_name, trigger_type, status, started_at, created_by)
            values (?, 'SCHEDULED', 'RUNNING', ?, 'system')
            """.trimIndent(),
            "question-schedule",
            Timestamp.from(Instant.now().minusSeconds(600)),
        )
        val checker = ReadinessChecker(
            dataSource,
            redisFactory("PONG"),
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(
                    schedulerStaleThresholdMinutes = 15,
                    schedulerMonitoredJobs = listOf("question-schedule"),
                ),
            ),
        )

        val response = checker.check()

        assertThat(response.ok).isFalse()
        assertThat(response.checks["scheduler"]?.ok).isFalse()
        assertThat(response.checks["scheduler"]?.message).contains("Stuck scheduler jobs")
        assertThat(response.checks["scheduler"]?.details?.get("stuckJobs").toString()).contains("question-schedule")
        assertThat(response.checks["scheduler"]?.details?.get("stuckJobs").toString()).contains("latestRunId")
    }

    private fun h2DataSource(): DataSource =
        h2DataSource(lastStartedAt = Instant.now(), seedJobs = true)

    private fun h2DataSource(lastStartedAt: Instant?, seedJobs: Boolean): DataSource {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:readiness-${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute(
            """
            create table scheduled_jobs (
                job_name varchar(120) primary key,
                enabled boolean not null default true,
                schedule_type varchar(40) not null,
                schedule_value varchar(120) not null,
                timeout_seconds integer not null default 300
            )
            """.trimIndent(),
        )
        jdbc.execute(
            """
            create table scheduled_job_runs (
                id bigserial primary key,
                job_name varchar(120) not null,
                trigger_type varchar(40) not null,
                status varchar(40) not null,
                started_at timestamp not null,
                error_message varchar(500),
                created_by varchar(120) not null
            )
            """.trimIndent(),
        )
        if (seedJobs) {
            listOf(
                "question-schedule",
                "question-push-outbox-dispatch",
                "user-stats-refresh",
                "admin-analytics-recent",
                "admin-analytics-correction",
            )
                .forEach { jobName ->
                    jdbc.update(
                        "insert into scheduled_jobs (job_name, enabled, schedule_type, schedule_value) values (?, true, 'FIXED_DELAY', 'test')",
                        jobName,
                    )
                    if (lastStartedAt != null) {
                        jdbc.update(
                            "insert into scheduled_job_runs (job_name, trigger_type, status, started_at, created_by) values (?, 'SCHEDULED', 'SUCCESS', ?, 'system')",
                            jobName,
                            Timestamp.from(lastStartedAt),
                        )
                    }
                }
        }
        return dataSource
    }

    private fun failingDataSource(): DataSource =
        object : DataSource {
            override fun getConnection(): Connection = throw SQLException("database down")
            override fun getConnection(username: String?, password: String?): Connection = throw SQLException("database down")
            override fun getLogWriter(): PrintWriter? = null
            override fun setLogWriter(out: PrintWriter?) = Unit
            override fun setLoginTimeout(seconds: Int) = Unit
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): Logger = Logger.getGlobal()
            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("unwrap unsupported")
            override fun isWrapperFor(iface: Class<*>?): Boolean = false
        }

    private fun redisFactory(ping: String? = "PONG", error: RuntimeException? = null): RedisConnectionFactory =
        Proxy.newProxyInstance(
            RedisConnectionFactory::class.java.classLoader,
            arrayOf(RedisConnectionFactory::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getConnection" -> {
                    if (error != null) throw error
                    redisConnection(ping)
                }
                "getConvertPipelineAndTxResults" -> true
                else -> defaultValue(method.returnType)
            }
        } as RedisConnectionFactory

    private fun redisConnection(ping: String?): RedisConnection =
        Proxy.newProxyInstance(
            RedisConnection::class.java.classLoader,
            arrayOf(RedisConnection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "ping" -> ping
                "close" -> null
                "isClosed" -> false
                else -> defaultValue(method.returnType)
            }
        } as RedisConnection

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            java.lang.Void.TYPE -> null
            else -> null
        }
}
