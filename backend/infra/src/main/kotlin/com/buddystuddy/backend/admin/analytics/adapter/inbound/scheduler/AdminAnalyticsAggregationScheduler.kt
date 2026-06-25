package com.buddystuddy.backend.admin.analytics.adapter.inbound.scheduler

import com.buddystuddy.backend.admin.analytics.application.port.inbound.AdminAnalyticsAggregationUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

@Component
@ConditionalOnProperty(prefix = "buddystuddy.analytics", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AdminAnalyticsAggregationScheduler(
    private val analytics: AdminAnalyticsAggregationUseCase,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${buddystuddy.analytics.recent-cron:0 */5 * * * *}")
    fun refreshRecent() {
        refresh("recent") { today -> analytics.refreshRecent(today) }
    }

    @Scheduled(cron = "\${buddystuddy.analytics.correction-cron:0 20 3 * * *}")
    fun refreshCorrection() {
        refresh("correction") { today -> analytics.refreshCorrection(today) }
    }

    private fun refresh(type: String, block: (LocalDate) -> Int) {
        val started = System.nanoTime()
        val today = LocalDate.now(ZoneOffset.UTC)
        runCatching {
            block(today)
        }.onSuccess { rows ->
            val durationMs = (System.nanoTime() - started) / 1_000_000.0
            logger.info(
                "admin_analytics_aggregation_completed type={} referenceDate={} rows={} durationMs={}",
                type,
                today,
                rows,
                "%.2f".format(durationMs),
            )
        }.onFailure { error ->
            logger.warn(
                "admin_analytics_aggregation_failed type={} referenceDate={} error={}",
                type,
                today,
                error.message,
                error,
            )
        }
    }
}
