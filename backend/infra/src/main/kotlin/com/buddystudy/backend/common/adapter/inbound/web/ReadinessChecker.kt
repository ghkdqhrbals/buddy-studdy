package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.dto.ReadinessCheckResponse
import com.buddystudy.backend.common.adapter.inbound.web.dto.ReadinessResponse
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
class ReadinessChecker(
    private val dataSource: DataSource,
    private val redisConnectionFactory: RedisConnectionFactory,
) {
    fun check(): ReadinessResponse {
        val checks = linkedMapOf(
            "database" to checkDatabase(),
            "redis" to checkRedis(),
        )
        return ReadinessResponse(
            ok = checks.values.all { it.ok },
            checks = checks,
        )
    }

    private fun checkDatabase(): ReadinessCheckResponse =
        runCatching {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("select 1")
                }
            }
            ReadinessCheckResponse(ok = true)
        }.getOrElse { error ->
            ReadinessCheckResponse(ok = false, message = error.safeMessage())
        }

    private fun checkRedis(): ReadinessCheckResponse =
        runCatching {
            val connection = redisConnectionFactory.connection
            try {
                val pong = connection.ping()
                if (pong == null || !pong.equals("PONG", ignoreCase = true)) {
                    throw IllegalStateException("Unexpected Redis ping response: $pong")
                }
            } finally {
                connection.close()
            }
            ReadinessCheckResponse(ok = true)
        }.getOrElse { error ->
            ReadinessCheckResponse(ok = false, message = error.safeMessage())
        }

    private fun Throwable.safeMessage(): String =
        listOfNotNull(javaClass.simpleName, message?.take(200))
            .joinToString(": ")
}
