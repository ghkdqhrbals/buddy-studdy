package com.buddystudy.backend.community.adapter.outbound.stream

import com.buddystudy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystudy.backend.config.BuddyStudyProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Primary
@Component
class AsyncPublicQuestionReactionPublisher(
    private val properties: BuddyStudyProperties,
    @param:Qualifier("publicQuestionReactionRedisStreamPublisher")
    private val delegate: PublicQuestionReactionPublishPort,
) : PublicQuestionReactionPublishPort, SmartLifecycle {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val queue = ArrayBlockingQueue<ViewEvent>(properties.streams.viewQueueCapacity.coerceAtLeast(1))
    private val running = AtomicBoolean(false)
    private val workerCount = properties.streams.viewPublisherConcurrency.coerceAtLeast(1)
    private val executor = Executors.newFixedThreadPool(workerCount) { task ->
        Thread(task, "public-question-view-publisher").apply { isDaemon = true }
    }

    override fun publishViewed(questionId: Long, userId: Long?): Boolean {
        if (!properties.streams.enabled) return false
        val accepted = queue.offer(ViewEvent(questionId, userId))
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

    override fun start() {
        if (running.compareAndSet(false, true)) {
            repeat(workerCount) {
                executor.execute(::publishLoop)
            }
        }
    }

    override fun stop() {
        running.set(false)
        executor.shutdownNow()
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun stop(callback: Runnable) {
        stop()
        callback.run()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE

    private fun publishLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val event = queue.take()
                val published = delegate.publishViewed(event.questionId, event.userId)
                if (!published) {
                    logger.warn(
                        "view_event_publish_failed questionId={} userId={}",
                        event.questionId,
                        event.userId,
                    )
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                logger.warn("view_event_publish_loop_failed error={}", error.message)
            }
        }
    }

    private data class ViewEvent(val questionId: Long, val userId: Long?)
}
