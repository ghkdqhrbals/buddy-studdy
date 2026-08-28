package com.buddystudy.backend.scheduler

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.scheduler.application.port.outbound.ScheduledJobHistoryRetentionPort
import com.buddystudy.backend.scheduler.application.service.ScheduledJobHistoryCleanupService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ScheduledJobHistoryCleanupServiceTest {
    @Test
    fun `cleans history in bounded batches and caps retention at seven days`(): Unit = runBlocking {
        val history = RecordingHistoryRetentionPort(2, 2, 1)
        val properties = BuddyStudyProperties(
            scheduler = BuddyStudyProperties.Scheduler(
                historyRetentionDays = 30,
                historyCleanupBatchSize = 2,
                historyCleanupMaxRowsPerRun = 5,
            ),
        )
        val now = Instant.parse("2026-08-28T00:00:00Z")

        val result = ScheduledJobHistoryCleanupService(history, properties).cleanup(now)

        assertThat(result.deletedRuns).isEqualTo(5)
        assertThat(result.batches).isEqualTo(3)
        assertThat(result.capped).isTrue()
        assertThat(result.cutoff).isEqualTo(Instant.parse("2026-08-21T00:00:00Z"))
        assertThat(history.calls.map { it.cutoff }).containsOnly(Instant.parse("2026-08-21T00:00:00Z"))
        assertThat(history.calls.map { it.limit }).containsExactly(2, 2, 1)
    }

    @Test
    fun `continues after a partial batch so retry parents can become deletable`(): Unit = runBlocking {
        val history = RecordingHistoryRetentionPort(1, 1, 0)
        val properties = BuddyStudyProperties(
            scheduler = BuddyStudyProperties.Scheduler(
                historyCleanupBatchSize = 100,
                historyCleanupMaxRowsPerRun = 1_000,
            ),
        )

        val result = ScheduledJobHistoryCleanupService(history, properties)
            .cleanup(Instant.parse("2026-08-28T00:00:00Z"))

        assertThat(result.deletedRuns).isEqualTo(2)
        assertThat(result.batches).isEqualTo(2)
        assertThat(result.capped).isFalse()
        assertThat(history.calls).hasSize(3)
    }

    private class RecordingHistoryRetentionPort(vararg deletedPerCall: Int) : ScheduledJobHistoryRetentionPort {
        private val results = deletedPerCall.toMutableList()
        val calls = mutableListOf<Call>()

        override suspend fun deleteExpiredTerminalRuns(
            cutoff: Instant,
            limit: Int,
        ): Int {
            calls += Call(cutoff, limit)
            return if (results.isEmpty()) 0 else results.removeAt(0)
        }
    }

    private data class Call(
        val cutoff: Instant,
        val limit: Int,
    )
}
