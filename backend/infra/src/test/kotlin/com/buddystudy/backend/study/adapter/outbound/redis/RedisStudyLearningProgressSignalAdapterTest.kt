package com.buddystudy.backend.study.adapter.outbound.redis

import com.buddystudy.backend.common.adapter.outbound.transaction.ReactiveAfterCommitAdapter
import com.buddystudy.backend.common.application.outbox.AfterCommitPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.data.redis.connection.ReactivePubSubCommands
import org.springframework.data.redis.connection.ReactiveRedisConnection
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.ReactiveSubscription
import org.springframework.data.redis.connection.SubscriptionListener
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.transaction.reactive.TransactionContextManager
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic reactive connections only: never connects to a configured Redis or backend. */
@Timeout(10)
class RedisStudyLearningProgressSignalAdapterTest {
    @Test
    fun `subscribe ACK and matching changes wake canonical reads with no message subscription gap`(): Unit = runBlocking {
        val fixture = Fixture()
        val connection = fixture.connection()
        val reads = AtomicInteger()
        val job = launch { fixture.adapter().changes("process_1").collect { reads.incrementAndGet() } }
        connection.subscribing.await()
        assertThat(connection.receiving).isTrue()
        assertThat(reads.get()).isZero()
        connection.acknowledge()
        eventually { reads.get() == 1 }
        connection.message("other_process")
        connection.message("process_1", channel = "other.channel.change.v1")
        delay(30)
        assertThat(reads.get()).isEqualTo(1)
        connection.message("process_1")
        eventually { reads.get() == 2 }
        // Lettuce may restore its same subscription after reconnect without
        // terminating receive(). Its fresh server ACK must trigger another read.
        connection.acknowledge()
        eventually { reads.get() == 3 }
        job.cancelAndJoin()
        assertThat(connection.receiverCancelled).isTrue()
        verify(connection.subscription).cancel()
        verify(connection.connection).closeLater()
    }

    @Test
    fun `failed receive reconnects with a new owned subscription and rechecks after ACK`(): Unit = runBlocking {
        val fixture = Fixture()
        val first = fixture.connection()
        val second = fixture.connection()
        val reads = AtomicInteger()
        val job = launch { fixture.adapter().changes("process_1").collect { reads.incrementAndGet() } }
        first.subscribing.await()
        first.acknowledge()
        eventually { reads.get() == 1 }
        first.messages.tryEmitError(IllegalStateException("synthetic disconnect"))
        second.subscribing.await()
        verify(first.subscription).cancel()
        verify(first.connection).closeLater()
        assertThat(reads.get()).isEqualTo(1)
        second.acknowledge()
        eventually { reads.get() == 2 }
        job.cancelAndJoin()
        verify(second.subscription).cancel()
        verify(second.connection).closeLater()
    }

    @Test
    fun `cancelling before subscribe ACK closes connection even when unsubscribe hangs`(): Unit = runBlocking {
        val fixture = Fixture()
        val connection = fixture.connection()
        `when`(connection.subscription.cancel()).thenReturn(Mono.never())
        val job = launch { fixture.adapter(ioTimeout = Duration.ofMillis(75)).changes("process_1").collect {} }
        connection.subscribing.await()
        withTimeout(1_000) { job.cancelAndJoin() }
        assertThat(connection.receiverCancelled).isTrue()
        verify(connection.subscription).cancel()
        verify(connection.connection).closeLater()
    }

    @Test
    fun `one watch cancellation cannot unsubscribe another correlation watch`(): Unit = runBlocking {
        val fixture = Fixture()
        val first = fixture.connection()
        val second = fixture.connection()
        val adapter = fixture.adapter()
        val firstJob = launch { adapter.changes("first").collect {} }
        first.subscribing.await()
        first.acknowledge()
        val reads = AtomicInteger()
        val secondJob = launch { adapter.changes("second").collect { reads.incrementAndGet() } }
        second.subscribing.await()
        second.acknowledge()
        eventually { reads.get() == 1 }
        firstJob.cancelAndJoin()
        assertThat(second.receiverCancelled).isFalse()
        second.message("second")
        eventually { reads.get() == 2 }
        secondJob.cancelAndJoin()
    }

    @Test
    fun `durable reconciliation still wakes while Redis subscription is unavailable`(): Unit = runBlocking {
        val fixture = Fixture()
        val connection = fixture.connection()
        val reads = AtomicInteger()
        val job = launch {
            fixture.adapter(reconcile = Duration.ofMillis(40), ioTimeout = Duration.ofSeconds(1))
                .changes("process_1").collect { reads.incrementAndGet() }
        }
        connection.subscribing.await()
        // Never acknowledge SUBSCRIBE: these reads must come from durable
        // reconciliation, including when the test host schedules this late.
        eventually { reads.get() >= 2 }
        job.cancelAndJoin()
        verify(connection.connection).closeLater()
    }

    @Test
    fun `publish contains only correlation ID and runs after transaction commit callback`(): Unit = runBlocking {
        val fixture = Fixture()
        val sent = AtomicInteger()
        `when`(fixture.redis.convertAndSend(CHANNEL, "process_1")).thenReturn(Mono.defer {
            sent.incrementAndGet()
            Mono.just(1L)
        })
        val adapter = fixture.adapter(afterCommit = ReactiveAfterCommitAdapter())
        mono {
            val manager = TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
            manager.initSynchronization()
            adapter.changed("process_1")
            assertThat(sent.get()).isZero()
            assertThat(manager.synchronizations).hasSize(1)
            manager.synchronizations.single().afterCommit().awaitSingleOrNull()
            Unit
        }.contextWrite(TransactionContextManager.createTransactionContext()).awaitSingle()
        assertThat(sent.get()).isEqualTo(1)
        verify(fixture.redis).convertAndSend(CHANNEL, "process_1")
    }

    @Test
    fun `publish outage or timeout cannot fail an already committed result`(): Unit = runBlocking {
        val fixture = Fixture()
        `when`(fixture.redis.convertAndSend(CHANNEL, "failed")).thenReturn(Mono.error(IllegalStateException("synthetic outage")))
        `when`(fixture.redis.convertAndSend(CHANNEL, "timeout")).thenReturn(Mono.never())
        val adapter = fixture.adapter(ioTimeout = Duration.ofMillis(40))
        withTimeout(500) {
            adapter.changed("failed")
            adapter.changed("timeout")
        }
        verify(fixture.redis).convertAndSend(CHANNEL, "failed")
        verify(fixture.redis).convertAndSend(CHANNEL, "timeout")
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(1_000) {
        while (!condition()) delay(5)
    }

    private class Fixture {
        val redis = mock(ReactiveStringRedisTemplate::class.java)
        private val factory = mock(ReactiveRedisConnectionFactory::class.java)
        private val connections = ArrayDeque<ConnectionProbe>()

        init {
            `when`(redis.connectionFactory).thenReturn(factory)
            `when`(factory.reactiveConnection).thenAnswer { connections.removeFirst().connection }
        }

        fun connection() = ConnectionProbe().also(connections::addLast)
        fun adapter(
            reconcile: Duration = Duration.ofSeconds(30),
            ioTimeout: Duration = Duration.ofSeconds(1),
            afterCommit: AfterCommitPort = object : AfterCommitPort {
                override suspend fun execute(action: suspend () -> Unit) = action()
            },
        ) = RedisStudyLearningProgressSignalAdapter(redis, afterCommit, reconcile, Duration.ofMillis(5), ioTimeout)
    }

    private class ConnectionProbe {
        val connection = mock(ReactiveRedisConnection::class.java)
        val subscription = mock(ReactiveSubscription::class.java)
        private val commands = mock(ReactivePubSubCommands::class.java)
        val messages = Sinks.many().multicast().onBackpressureBuffer<ReactiveSubscription.Message<ByteBuffer, ByteBuffer>>()
        private val ack = Sinks.empty<Void>()
        val subscribing = CompletableDeferred<Unit>()
        private lateinit var listener: SubscriptionListener
        var receiving = false
        var receiverCancelled = false

        init {
            `when`(connection.pubSubCommands()).thenReturn(commands)
            `when`(connection.closeLater()).thenReturn(Mono.empty())
            `when`(commands.createSubscription(any(SubscriptionListener::class.java))).thenAnswer {
                listener = it.getArgument(0)
                Mono.just(subscription)
            }
            `when`(subscription.receive()).thenReturn(messages.asFlux()
                .doOnSubscribe { receiving = true }
                .doOnCancel { receiverCancelled = true })
            `when`(subscription.subscribe(ByteBuffer.wrap(CHANNEL.toByteArray(UTF_8)))).thenReturn(Mono.defer {
                check(receiving)
                subscribing.complete(Unit)
                ack.asMono()
            })
            `when`(subscription.cancel()).thenReturn(Mono.empty())
        }

        fun acknowledge() {
            listener.onChannelSubscribed(CHANNEL.toByteArray(UTF_8), 1)
            ack.tryEmitEmpty()
        }

        fun message(body: String, channel: String = CHANNEL) {
            assertThat(messages.tryEmitNext(ReactiveSubscription.ChannelMessage(
                ByteBuffer.wrap(channel.toByteArray(UTF_8)), ByteBuffer.wrap(body.toByteArray(UTF_8)),
            )).isSuccess).isTrue()
        }
    }

    private companion object {
        const val CHANNEL = RedisStudyLearningProgressSignalAdapter.CHANNEL
    }
}
