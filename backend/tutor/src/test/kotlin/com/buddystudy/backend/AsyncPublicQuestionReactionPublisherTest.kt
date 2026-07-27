package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.community.adapter.outbound.stream.AsyncPublicQuestionReactionPublisher
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionViewLocalization
import com.buddystudy.backend.config.BuddyStudyProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class AsyncPublicQuestionReactionPublisherTest {
    private val closeables = mutableListOf<AsyncPublicQuestionReactionPublisher>()

    @AfterEach
    fun tearDown() = runBlocking {
        closeables.forEach { it.stop() }
    }

    @Test
    fun `view publish returns before slow redis delegate finishes`(): Unit = runBlocking {
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
    fun `view publish returns false when async queue is full`(): Unit = runBlocking {
        val delegate = BlockingReactionPublisher()
        val publisher = asyncPublisher(delegate, capacity = 1).also {
            closeables += it
            it.start()
        }

        assertThat(publisher.publishViewed(1, null)).isTrue()
        assertThat(delegate.awaitStarted(1, TimeUnit.SECONDS)).isTrue()
        assertThat(publisher.publishViewed(2, null)).isTrue()
        assertThat(publisher.publishViewed(3, null)).isFalse()
        delegate.release()
    }

    private fun asyncPublisher(
        delegate: PublicQuestionReactionPublishPort,
        capacity: Int,
    ): AsyncPublicQuestionReactionPublisher {
        val properties = BuddyStudyProperties().apply {
            streams.enabled = true
            streams.viewQueueCapacity = capacity
            streams.viewPublisherConcurrency = 1
        }
        return AsyncPublicQuestionReactionPublisher(properties, delegate)
    }

    private data class ViewEvent(val questionId: Long, val userId: Long?)

    private class SlowRecordingReactionPublisher(
        private val delayMs: Long = 0,
    ) : PublicQuestionReactionPublishPort {
        private val latch = CountDownLatch(1)
        val events = mutableListOf<ViewEvent>()

        override suspend fun publishViewed(
            questionId: Long,
            userId: Long?,
            localization: PublicQuestionViewLocalization?,
        ): Boolean {
            if (delayMs > 0) Thread.sleep(delayMs)
            events += ViewEvent(questionId, userId)
            latch.countDown()
            return true
        }

        fun await(timeout: Long, unit: TimeUnit): Boolean =
            latch.await(timeout, unit)
    }

    private class BlockingReactionPublisher : PublicQuestionReactionPublishPort {
        private val started = CountDownLatch(1)
        private val release = CountDownLatch(1)

        override suspend fun publishViewed(
            questionId: Long,
            userId: Long?,
            localization: PublicQuestionViewLocalization?,
        ): Boolean {
            started.countDown()
            release.await()
            return true
        }

        fun awaitStarted(timeout: Long, unit: TimeUnit): Boolean =
            started.await(timeout, unit)

        fun release() {
            release.countDown()
        }
    }
}
