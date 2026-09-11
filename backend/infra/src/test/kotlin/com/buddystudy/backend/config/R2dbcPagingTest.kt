package com.buddystudy.backend.config

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageRequest
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class R2dbcPagingTest {
    @Test
    fun `page is fully consumed before count is submitted on the transaction connection`() = runBlocking<Unit> {
        val content = Sinks.many().unicast().onBackpressureBuffer<Row>()
        val countCalls = AtomicInteger()
        val selectedQueries = mutableListOf<Query>()
        val countedQueries = mutableListOf<Query>()
        val template = template(content.asFlux(), countCalls, selectedQueries, countedQueries)
        val query = Query.query(Criteria.where("user_id").`is`(7L))
        val countQuery = Query.query(Criteria.where("user_id").`is`(7L))
        val pageable = PageRequest.of(2, 2)
        val page = async(start = CoroutineStart.UNDISPATCHED) {
            template.selectPage(query, countQuery, Row::class.java, pageable)
        }
        yield()

        assertThat(countCalls.get()).isZero()
        content.tryEmitNext(Row(5)).orThrow()
        yield()
        assertThat(countCalls.get()).isZero()
        content.tryEmitComplete().orThrow()

        val result = page.await()
        assertThat(result.content).containsExactly(Row(5))
        assertThat(result.totalElements).isEqualTo(7)
        assertThat(result.pageable).isEqualTo(pageable)
        assertThat(selectedQueries.single().limit).isEqualTo(2)
        assertThat(selectedQueries.single().offset).isEqualTo(4)
        assertThat(countedQueries.single()).isSameAs(countQuery)
        assertThat(countCalls.get()).isEqualTo(1)
    }

    @Test
    fun `failed page does not enqueue a count behind the failed query`() = runBlocking<Unit> {
        val failure = IllegalStateException("Synthetic connection queue failure")
        val countCalls = AtomicInteger()
        val template = template(Flux.error(failure), countCalls)

        val result = runCatching {
            template.selectPage(Query.empty(), Query.empty(), Row::class.java, PageRequest.of(0, 10))
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java).hasMessage(failure.message)
        assertThat(countCalls.get()).isZero()
    }

    @Test
    fun `cancelled page cancels its active query without leaving a count queued`() = runBlocking<Unit> {
        val cancelled = AtomicBoolean()
        val countCalls = AtomicInteger()
        val template = template(Flux.never<Row>().doOnCancel { cancelled.set(true) }, countCalls)
        val page = async(start = CoroutineStart.UNDISPATCHED) {
            template.selectPage(Query.empty(), Query.empty(), Row::class.java, PageRequest.of(0, 10))
        }
        yield()
        page.cancelAndJoin()

        assertThat(cancelled.get()).isTrue()
        assertThat(countCalls.get()).isZero()
    }

    private fun template(
        content: Flux<Row>,
        countCalls: AtomicInteger,
        selectedQueries: MutableList<Query> = mutableListOf(),
        countedQueries: MutableList<Query> = mutableListOf(),
    ): R2dbcEntityTemplate = Mockito.mock(R2dbcEntityTemplate::class.java) { invocation ->
        when (invocation.method.name) {
            "select" -> {
                selectedQueries += invocation.getArgument<Query>(0)
                content
            }
            "count" -> {
                countCalls.incrementAndGet()
                countedQueries += invocation.getArgument<Query>(0)
                Mono.just(7L)
            }
            else -> throw AssertionError("Unexpected template operation: ${invocation.method.name}")
        }
    }

    private data class Row(val id: Long)
}
