package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.config.BuddyStudyProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.http.HttpStatus
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

class HealthControllerTest {
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
}
