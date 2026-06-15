package com.buddystuddy.backend

import com.buddystuddy.backend.community.adapter.outbound.stream.AsyncPublicQuestionReactionPublisher
import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystuddy.backend.config.BuddyStuddyProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class AsyncPublicQuestionReactionPublisherTest {
    private val closeables = mutableListOf<AsyncPublicQuestionReactionPublisher>()

    @AfterEach
    fun tearDown() {
        closeables.forEach { it.stop() }
    }

    @Test
    fun `view publish returns before slow redis delegate finishes`() {
        val delegate = SlowRecordingReactionPublisher(delayMs = 350)
        val publisher = asyncPublisher(delegate, capacity = 10).also {
            closeables += it
            it.start()
        }

        val elapsedMs = measureTimeMillis {
            assertThat(publisher.publishViewed(20, 4)).isTrue()
        }

        assertThat(elapsedMs).isLessThan(100)
        assertThat(delegate.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(delegate.events).containsExactly(ViewEvent(20, 4))
    }

    @Test
    fun `view publish returns false when async queue is full`() {
        val publisher = asyncPublisher(SlowRecordingReactionPublisher(), capacity = 1)

        assertThat(publisher.publishViewed(1, null)).isTrue()
        assertThat(publisher.publishViewed(2, null)).isFalse()
    }

    private fun asyncPublisher(
        delegate: PublicQuestionReactionPublishPort,
        capacity: Int,
    ): AsyncPublicQuestionReactionPublisher {
        val properties = BuddyStuddyProperties().apply {
            streams.enabled = true
            streams.viewQueueCapacity = capacity
        }
        return AsyncPublicQuestionReactionPublisher(properties, delegate)
    }

    private data class ViewEvent(val questionId: Long, val userId: Long?)

    private class SlowRecordingReactionPublisher(
        private val delayMs: Long = 0,
    ) : PublicQuestionReactionPublishPort {
        private val latch = CountDownLatch(1)
        val events = mutableListOf<ViewEvent>()

        override fun publishViewed(questionId: Long, userId: Long?): Boolean {
            if (delayMs > 0) Thread.sleep(delayMs)
            events += ViewEvent(questionId, userId)
            latch.countDown()
            return true
        }

        fun await(timeout: Long, unit: TimeUnit): Boolean =
            latch.await(timeout, unit)
    }
}
