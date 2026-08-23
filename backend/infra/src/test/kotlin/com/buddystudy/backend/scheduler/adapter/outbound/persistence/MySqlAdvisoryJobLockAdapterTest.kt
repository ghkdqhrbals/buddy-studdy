package com.buddystudy.backend.scheduler.adapter.outbound.persistence

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Result
import io.r2dbc.spi.Statement
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import reactor.core.publisher.Mono
import java.util.function.BiFunction
import org.junit.jupiter.api.Test

class MySqlAdvisoryJobLockAdapterTest {
    @Test
    fun `consumes unlock result before returning connection`(): Unit = runBlocking {
        val factory = mock(ConnectionFactory::class.java)
        val connection = mock(Connection::class.java)
        val acquireStatement = mock(Statement::class.java)
        val releaseStatement = mock(Statement::class.java)
        val acquireResult = mock(Result::class.java)
        val releaseResult = mock(Result::class.java)

        `when`(factory.create()).thenReturn(Mono.just(connection))
        `when`(connection.createStatement("select get_lock(?, 0) as acquired"))
            .thenReturn(acquireStatement)
        `when`(connection.createStatement("select release_lock(?) as released"))
            .thenReturn(releaseStatement)
        `when`(acquireStatement.bind(0, "event-outbox-dispatch")).thenReturn(acquireStatement)
        `when`(releaseStatement.bind(0, "event-outbox-dispatch")).thenReturn(releaseStatement)
        `when`(acquireStatement.execute()).thenReturn(Mono.just(acquireResult))
        `when`(releaseStatement.execute()).thenReturn(Mono.just(releaseResult))
        `when`(acquireResult.map<Boolean>(any<BiFunction<io.r2dbc.spi.Row, io.r2dbc.spi.RowMetadata, Boolean>>()))
            .thenReturn(Mono.just(true))
        `when`(releaseResult.map<Boolean>(any<BiFunction<io.r2dbc.spi.Row, io.r2dbc.spi.RowMetadata, Boolean>>()))
            .thenReturn(Mono.just(true))
        `when`(connection.close()).thenReturn(Mono.empty())

        val adapter = MySqlAdvisoryJobLockAdapter(factory)

        assertThat(adapter.tryAcquire("event-outbox-dispatch")).isTrue()
        adapter.release("event-outbox-dispatch")

        verify(releaseResult, times(1))
            .map<Boolean>(any<BiFunction<io.r2dbc.spi.Row, io.r2dbc.spi.RowMetadata, Boolean>>())
        verify(connection, times(1)).close()
    }
}
