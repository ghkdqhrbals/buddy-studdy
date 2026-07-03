package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.common.adapter.inbound.web.dto.ReadinessResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.lang.reflect.ParameterizedType
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.logging.Logger
import javax.sql.DataSource

class HealthControllerTest {
    @Test
    fun `readiness endpoints expose concrete readiness response type`() {
        val readinessReturnType = HealthController::class.java.getDeclaredMethod("readiness").genericReturnType
        val dependencyReturnType = HealthController::class.java.getDeclaredMethod("dependencyReadiness").genericReturnType

        assertThat(readinessReturnType).isEqualTo(readinessResponseEntityType())
        assertThat(dependencyReturnType).isEqualTo(readinessResponseEntityType())
    }

    @Test
    fun `readiness returns ok when dependencies are ready`() {
        val controller = HealthController(
            ReadinessChecker(
                dataSource = singleConnectionDataSource(),
                redisConnectionFactory = redisFactory("PONG"),
                properties = BuddyStudyProperties(
                    monitoring = BuddyStudyProperties.Monitoring(schedulerReadinessEnabled = false),
                ),
            ),
        )

        val response = controller.readiness()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).hasFieldOrPropertyWithValue("ok", true)
    }

    @Test
    fun `readiness returns service unavailable when a dependency fails`() {
        val controller = HealthController(
            ReadinessChecker(
                dataSource = failingDataSource(),
                redisConnectionFactory = redisFactory("PONG"),
                properties = BuddyStudyProperties(
                    monitoring = BuddyStudyProperties.Monitoring(schedulerReadinessEnabled = false),
                ),
            ),
        )

        val response = controller.readiness()

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.body).hasFieldOrPropertyWithValue("ok", false)
    }

    @Test
    fun `readiness returns service unavailable when scheduler is stale`() {
        val controller = HealthController(
            ReadinessChecker(
                dataSource = schedulerDataSource(lastStartedAt = Instant.now().minusSeconds(3_600)),
                redisConnectionFactory = redisFactory("PONG"),
                properties = BuddyStudyProperties(
                    monitoring = BuddyStudyProperties.Monitoring(
                        schedulerStaleThresholdMinutes = 15,
                        schedulerMonitoredJobs = listOf("question-schedule"),
                    ),
                ),
            ),
        )

        val response = controller.readiness()

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.body).hasFieldOrPropertyWithValue("ok", false)
        assertThat(response.body.toString()).contains("scheduler")
        assertThat(response.body.toString()).contains("Stale scheduler jobs")
    }

    @Test
    fun `dependency readiness returns ok when hard dependencies are ready`() {
        val controller = HealthController(
            ReadinessChecker(
                dataSource = singleConnectionDataSource(),
                redisConnectionFactory = redisFactory("PONG"),
                properties = BuddyStudyProperties(),
            ),
        )

        val response = controller.dependencyReadiness()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).hasFieldOrPropertyWithValue("ok", true)
    }

    @Test
    fun `dependency readiness excludes stale scheduler so Kubernetes does not evict serving pods`() {
        val controller = HealthController(
            ReadinessChecker(
                dataSource = schedulerDataSource(lastStartedAt = Instant.now().minusSeconds(3_600)),
                redisConnectionFactory = redisFactory("PONG"),
                properties = BuddyStudyProperties(
                    monitoring = BuddyStudyProperties.Monitoring(
                        schedulerStaleThresholdMinutes = 15,
                        schedulerMonitoredJobs = listOf("question-schedule"),
                    ),
                ),
            ),
        )

        val response = controller.dependencyReadiness()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).hasFieldOrPropertyWithValue("ok", true)
        assertThat(response.body?.checks).containsKeys("database", "redis")
        assertThat(response.body?.checks).doesNotContainKey("scheduler")
    }

    @Test
    fun `dependency readiness returns service unavailable when a hard dependency fails`() {
        val controller = HealthController(
            ReadinessChecker(
                dataSource = failingDataSource(),
                redisConnectionFactory = redisFactory("PONG"),
                properties = BuddyStudyProperties(),
            ),
        )

        val response = controller.dependencyReadiness()

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.body).hasFieldOrPropertyWithValue("ok", false)
    }

    private fun singleConnectionDataSource(): DataSource =
        object : DataSource {
            override fun getConnection(): Connection =
                Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "createStatement" -> Proxy.newProxyInstance(
                            java.sql.Statement::class.java.classLoader,
                            arrayOf(java.sql.Statement::class.java),
                        ) { _, statementMethod, _ ->
                            when (statementMethod.name) {
                                "execute" -> true
                                "close" -> null
                                else -> defaultValue(statementMethod.returnType)
                            }
                        }
                        "close" -> null
                        else -> defaultValue(method.returnType)
                    }
                } as Connection

            override fun getConnection(username: String?, password: String?): Connection = getConnection()
            override fun getLogWriter(): PrintWriter? = null
            override fun setLogWriter(out: PrintWriter?) = Unit
            override fun setLoginTimeout(seconds: Int) = Unit
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): Logger = Logger.getGlobal()
            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("not wrapped")
            override fun isWrapperFor(iface: Class<*>?): Boolean = false
        }

    private fun failingDataSource(): DataSource =
        object : DataSource {
            override fun getConnection(): Connection = throw SQLException("database down")
            override fun getConnection(username: String?, password: String?): Connection = getConnection()
            override fun getLogWriter(): PrintWriter? = null
            override fun setLogWriter(out: PrintWriter?) = Unit
            override fun setLoginTimeout(seconds: Int) = Unit
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): Logger = Logger.getGlobal()
            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("not wrapped")
            override fun isWrapperFor(iface: Class<*>?): Boolean = false
        }

    private fun redisFactory(pong: String): RedisConnectionFactory =
        Proxy.newProxyInstance(
            RedisConnectionFactory::class.java.classLoader,
            arrayOf(RedisConnectionFactory::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getConnection" -> redisConnection(pong)
                "getConvertPipelineAndTxResults" -> true
                else -> defaultValue(method.returnType)
            }
        } as RedisConnectionFactory

    private fun redisConnection(pong: String): RedisConnection =
        Proxy.newProxyInstance(
            RedisConnection::class.java.classLoader,
            arrayOf(RedisConnection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "ping" -> pong
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

    private fun schedulerDataSource(lastStartedAt: Instant): DataSource {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:health-controller-${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
        jdbc.update(
            "insert into scheduled_jobs (job_name, enabled, schedule_type, schedule_value) values (?, true, 'FIXED_DELAY', '30s')",
            "question-schedule",
        )
        jdbc.update(
            "insert into scheduled_job_runs (job_name, trigger_type, status, started_at, created_by) values (?, 'SCHEDULED', 'SUCCESS', ?, 'system')",
            "question-schedule",
            Timestamp.from(lastStartedAt),
        )
        return dataSource
    }

    private fun readinessResponseEntityType(): ParameterizedType =
        object : ParameterizedType {
            override fun getActualTypeArguments(): Array<java.lang.reflect.Type> = arrayOf(ReadinessResponse::class.java)
            override fun getRawType(): java.lang.reflect.Type = ResponseEntity::class.java
            override fun getOwnerType(): java.lang.reflect.Type? = null
        }
}
