package com.buddystudy.backend.study.adapter.outbound.redis

import com.buddystudy.backend.common.application.outbox.AfterCommitPort
import com.buddystudy.backend.study.application.port.outbound.StudyLearningProgressSignalPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.connection.ReactiveRedisConnection
import org.springframework.data.redis.connection.ReactiveSubscription
import org.springframework.data.redis.connection.SubscriptionListener
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

/** Redis is a lossy notification channel; it never holds a question, answer, owner or process status. */
@Component
class RedisStudyLearningProgressSignalAdapter internal constructor(
    private val redis: ReactiveStringRedisTemplate,
    private val afterCommit: AfterCommitPort,
    private val reconcileInterval: Duration,
    private val retryDelay: Duration,
    private val ioTimeout: Duration,
) : StudyLearningProgressSignalPort {
    @Autowired
    constructor(redis: ReactiveStringRedisTemplate, afterCommit: AfterCommitPort) : this(
        redis, afterCommit, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(1),
    )

    override suspend fun changed(correlationId: String) {
        if (!CORRELATION_ID.matches(correlationId)) return
        afterCommit.execute {
            try {
                withTimeoutOrNull(ioTimeout.toMillis()) {
                    redis.convertAndSend(CHANNEL, correlationId).awaitSingle()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // This runs after commit. A Redis outage cannot undo or fail a
                // durable question/grade; subscribers also reconcile slowly.
                logger.warn("study_learning_progress_signal_publish_failed errorType={}", failure.javaClass.simpleName)
            }
        }
    }

    override fun changes(correlationId: String): Flow<Unit> = channelFlow {
        require(CORRELATION_ID.matches(correlationId)) { "Invalid learning process correlation ID." }
        val expected = ByteBuffer.wrap(correlationId.toByteArray(UTF_8)).asReadOnlyBuffer()
        val topic = CHANNEL.toByteArray(UTF_8)
        launch {
            while (isActive) {
                delay(reconcileInterval.toMillis())
                send(Unit)
            }
        }
        var reportedFailure = false
        while (isActive) {
            var connection: ReactiveRedisConnection? = null
            var subscription: ReactiveSubscription? = null
            try {
                // A dedicated connection prevents cancelling one watch from
                // unsubscribing another watch on this same Redis channel.
                val currentConnection = redis.connectionFactory.reactiveConnection
                connection = currentConnection
                val currentSubscription = withTimeout(ioTimeout.toMillis()) {
                    currentConnection.pubSubCommands().createSubscription(object : SubscriptionListener {
                        override fun onChannelSubscribed(channel: ByteArray, count: Long) {
                            if (channel.contentEquals(topic)) trySend(Unit)
                        }
                    }).awaitSingle()
                }
                subscription = currentSubscription
                coroutineScope {
                    val receiver = async(start = CoroutineStart.UNDISPATCHED) {
                        currentSubscription.receive().asFlow().collect { message ->
                            if (message.channel == ByteBuffer.wrap(topic) && message.message == expected) send(Unit)
                        }
                    }
                    // Install the receiver before SUBSCRIBE. Its ACK supplies
                    // the initial read, closing the lookup/subscribe race; an
                    // automatic Redis resubscription supplies the same hint.
                    withTimeout(ioTimeout.toMillis()) {
                        currentSubscription.subscribe(ByteBuffer.wrap(topic)).awaitSingleOrNull()
                    }
                    reportedFailure = false
                    receiver.await()
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (!reportedFailure) {
                    logger.warn("study_learning_progress_signal_subscribe_failed errorType={}", failure.javaClass.simpleName)
                    reportedFailure = true
                }
            } finally {
                // receiveLater explicitly cannot clean up cancellation while
                // its subscription ACK is pending. Own both resources instead,
                // and close even when UNSUBSCRIBE itself never acknowledges.
                withContext(NonCancellable) {
                    runCatching { withTimeoutOrNull(ioTimeout.toMillis()) { subscription?.cancel()?.awaitSingleOrNull() } }
                    runCatching { withTimeoutOrNull(ioTimeout.toMillis()) { connection?.closeLater()?.awaitSingleOrNull() } }
                }
            }
            delay(retryDelay.toMillis())
        }
    }.buffer(Channel.CONFLATED)

    companion object {
        internal const val CHANNEL = "study.learning-process.change.v1"
        private val CORRELATION_ID = Regex("[A-Za-z0-9_-]{1,191}")
        private val logger = LoggerFactory.getLogger(RedisStudyLearningProgressSignalAdapter::class.java)
    }
}
