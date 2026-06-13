package com.buddystuddy.backend.stats.adapter.inbound.scheduler

import com.buddystuddy.backend.stats.application.port.inbound.RefreshUserStatsUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "buddystuddy.stats", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class UserStatsRefreshScheduler(
    private val refreshUserStats: RefreshUserStatsUseCase,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${buddystuddy.stats.cron:0 */5 * * * *}")
    fun refresh() {
        val started = System.nanoTime()
        val now = Instant.now()
        runCatching {
            refreshUserStats.refreshAll(now)
        }.onSuccess {
            val durationMs = (System.nanoTime() - started) / 1_000_000.0
            logger.info("user_stats_refresh_completed durationMs={}", "%.2f".format(durationMs))
        }.onFailure { error ->
            logger.warn("user_stats_refresh_failed error={}", error.message, error)
        }
    }
}
