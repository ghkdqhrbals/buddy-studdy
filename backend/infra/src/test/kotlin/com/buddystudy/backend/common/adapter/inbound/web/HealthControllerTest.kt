package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.config.BuddyStudyProperties
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.connection.ReactiveRedisConnection
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import reactor.core.publisher.Mono

class HealthControllerTest {
    @Test
    fun `health endpoint remains lightweight`(): Unit = runBlocking {
        val controller = HealthController(checker(healthyRedis()))

        assertThat(controller.health().ok).isTrue()
    }

    @Test
    fun `readiness returns ok when r2dbc and redis are available`(): Unit = runBlocking {
        val response = HealthController(checker(healthyRedis())).readiness()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.checks?.get("database")?.ok).isTrue()
        assertThat(response.body?.checks?.get("redis")?.ok).isTrue()
    }

    @Test
    fun `readiness returns service unavailable when redis fails`(): Unit = runBlocking {
        val redis = mock(ReactiveRedisConnectionFactory::class.java)
        val connection = mock(ReactiveRedisConnection::class.java)
        `when`(redis.reactiveConnection).thenReturn(connection)
        `when`(connection.ping()).thenReturn(Mono.error(IllegalStateException("redis unavailable")))

        val response = HealthController(checker(redis)).readiness()

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.body?.checks?.get("redis")?.message).contains("redis unavailable")
    }

    private fun checker(redis: ReactiveRedisConnectionFactory): ReadinessChecker =
        ReadinessChecker(
            DatabaseClient.create(ConnectionFactories.get("r2dbc:h2:mem:///health;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")),
            redis,
            BuddyStudyProperties(
                monitoring = BuddyStudyProperties.Monitoring(schedulerReadinessEnabled = false),
            ),
        )

    private fun healthyRedis(): ReactiveRedisConnectionFactory {
        val redis = mock(ReactiveRedisConnectionFactory::class.java)
        val connection = mock(ReactiveRedisConnection::class.java)
        `when`(redis.reactiveConnection).thenReturn(connection)
        `when`(connection.ping()).thenReturn(Mono.just("PONG"))
        return redis
    }
}
