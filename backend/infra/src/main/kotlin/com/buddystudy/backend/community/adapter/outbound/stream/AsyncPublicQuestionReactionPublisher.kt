package com.buddystudy.backend.community.adapter.outbound.stream

import com.buddystudy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystudy.backend.config.BuddyStudyProperties
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Primary
@Component
class AsyncPublicQuestionReactionPublisher(
    private val properties: BuddyStudyProperties,
    @param:Qualifier("publicQuestionReactionRedisStreamPublisher")
    private val delegate: PublicQuestionReactionPublishPort,
) : PublicQuestionReactionPublishPort, SmartLifecycle {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val workerCount = properties.streams.viewPublisherConcurrency.coerceAtLeast(1)
    @Volatile
    private var workerScope: CoroutineScope? = null
    @Volatile
    private var queue: Channel<ViewEvent>? = null

    override suspend fun publishViewed(questionId: Long, userId: Long?): Boolean {
        if (!properties.streams.enabled) return false
        val accepted = queue?.trySend(ViewEvent(questionId, userId))?.isSuccess == true
        if (!accepted) {
            logger.warn(
                "view_event_queue_full questionId={} userId={} capacity={}",
                questionId,
                userId,
                properties.streams.viewQueueCapacity,
            )
        }
        return accepted
    }

    @Synchronized
    override fun start() {
        if (running.compareAndSet(false, true)) {
            val startedQueue = Channel<ViewEvent>(properties.streams.viewQueueCapacity.coerceAtLeast(1))
            val startedScope = CoroutineScope(
                SupervisorJob() + Dispatchers.IO + CoroutineName("public-question-view-publisher"),
            )
            queue = startedQueue
            workerScope = startedScope
            repeat(workerCount) {
                startedScope.launch { publishLoop(startedQueue) }
            }
        }
    }

    @Synchronized
    override fun stop() {
        running.set(false)
        queue?.close()
        queue = null
        workerScope?.cancel()
        workerScope = null
    }

    override fun stop(callback: Runnable) {
        stop()
        callback.run()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE

    private suspend fun publishLoop(events: Channel<ViewEvent>) {
        for (event in events) {
            if (!running.get()) break
            try {
                val published = delegate.publishViewed(event.questionId, event.userId)
                if (!published) {
                    logger.warn(
                        "view_event_publish_failed questionId={} userId={}",
                        event.questionId,
                        event.userId,
                    )
                }
            } catch (error: Exception) {
                logger.warn("view_event_publish_loop_failed error={}", error.message)
            }
        }
    }

    private data class ViewEvent(val questionId: Long, val userId: Long?)
}
