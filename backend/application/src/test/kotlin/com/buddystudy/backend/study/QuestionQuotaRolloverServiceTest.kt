package com.buddystudy.backend.study

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.port.outbound.QuestionQuotaRolloverPort
import com.buddystudy.backend.study.application.service.QuestionQuotaRolloverService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class QuestionQuotaRolloverServiceTest {
    @Test
    fun `rollover drains every full batch using one stable cutoff instant`(): Unit = runBlocking {
        val cutoff = Instant.parse("2026-08-01T00:00:00Z")
        val port = RecordingRolloverPort(ArrayDeque(listOf(2, 2, 1)))
        val properties = BuddyStudyProperties().apply { quota.rollover.batchSize = 2 }
        val service = QuestionQuotaRolloverService(properties, port)

        val result = service.rolloverDue(cutoff)

        assertThat(result.rolledOver).isEqualTo(5)
        assertThat(port.dueCalls).containsExactly(
            cutoff to 2,
            cutoff to 2,
            cutoff to 2,
        )
    }

    @Test
    fun `rollover batch size is clamped before reaching persistence`(): Unit = runBlocking {
        val cutoff = Instant.parse("2026-08-01T00:00:00Z")
        val port = RecordingRolloverPort(ArrayDeque(listOf(0)))
        val properties = BuddyStudyProperties().apply { quota.rollover.batchSize = 20_000 }
        val service = QuestionQuotaRolloverService(properties, port)

        service.rolloverDue(cutoff)

        assertThat(port.dueCalls).containsExactly(cutoff to 1_000)
    }

    @Test
    fun `rollover stops after the configured number of full batches`(): Unit = runBlocking {
        val cutoff = Instant.parse("2026-08-01T00:00:00Z")
        val port = RecordingRolloverPort(ArrayDeque(listOf(2, 2, 2, 1)))
        val properties = BuddyStudyProperties().apply {
            quota.rollover.batchSize = 2
            quota.rollover.maxBatchesPerRun = 3
        }

        val result = QuestionQuotaRolloverService(properties, port).rolloverDue(cutoff)

        assertThat(result.rolledOver).isEqualTo(6)
        assertThat(port.dueCalls).hasSize(3)
    }

    @Test
    fun `request fallback delegates the exact user and time to persistence`(): Unit = runBlocking {
        val cutoff = Instant.parse("2026-08-01T00:00:00Z")
        val port = RecordingRolloverPort(ArrayDeque(), userResult = true)
        val service = QuestionQuotaRolloverService(BuddyStudyProperties(), port)

        assertThat(service.rolloverUserIfDue(73, cutoff)).isTrue()
        assertThat(port.userCalls).containsExactly(73L to cutoff)
    }

    private class RecordingRolloverPort(
        private val batchResults: ArrayDeque<Int>,
        private val userResult: Boolean = false,
    ) : QuestionQuotaRolloverPort {
        val dueCalls = mutableListOf<Pair<Instant, Int>>()
        val userCalls = mutableListOf<Pair<Long, Instant>>()

        override suspend fun rolloverDue(at: Instant, batchSize: Int): Int {
            dueCalls += at to batchSize
            return batchResults.removeFirst()
        }

        override suspend fun rolloverUserIfDue(userId: Long, at: Instant): Boolean {
            userCalls += userId to at
            return userResult
        }
    }
}
