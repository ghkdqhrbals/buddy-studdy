package com.buddystudy.backend.common.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

class ReadinessCheckerTest {
    @Test
    fun `readiness is ok when database and redis are reachable`() {
        val checker = ReadinessChecker(h2DataSource(), redisFactory("PONG"))

        val response = checker.check()

        assertThat(response.ok).isTrue()
        assertThat(response.checks["database"]?.ok).isTrue()
        assertThat(response.checks["redis"]?.ok).isTrue()
    }

    @Test
    fun `readiness fails when database is unavailable`() {
        val checker = ReadinessChecker(failingDataSource(), redisFactory("PONG"))

        val response = checker.check()

        assertThat(response.ok).isFalse()
        assertThat(response.checks["database"]?.ok).isFalse()
        assertThat(response.checks["database"]?.message).contains("SQLException")
        assertThat(response.checks["redis"]?.ok).isTrue()
    }

    @Test
    fun `readiness fails when redis ping is unavailable`() {
        val checker = ReadinessChecker(h2DataSource(), redisFactory(error = IllegalStateException("redis down")))

        val response = checker.check()

        assertThat(response.ok).isFalse()
        assertThat(response.checks["database"]?.ok).isTrue()
        assertThat(response.checks["redis"]?.ok).isFalse()
        assertThat(response.checks["redis"]?.message).contains("redis down")
    }

    private fun h2DataSource(): DataSource =
        DriverManagerDataSource("jdbc:h2:mem:readiness;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")

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
