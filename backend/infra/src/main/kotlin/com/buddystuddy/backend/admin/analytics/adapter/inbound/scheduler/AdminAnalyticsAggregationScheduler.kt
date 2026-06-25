package com.buddystuddy.backend.admin.analytics.adapter.inbound.scheduler

import com.buddystuddy.backend.admin.analytics.application.port.inbound.AdminAnalyticsAggregationUseCase
import com.buddystuddy.backend.scheduler.application.model.JobTriggerType
import com.buddystuddy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystuddy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

@Component
@ConditionalOnProperty(prefix = "buddystuddy.analytics", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AdminAnalyticsAggregationScheduler(
    private val jobs: ManagedJobExecutionUseCase,
    private val recentJob: AdminAnalyticsRecentJob,
    private val correctionJob: AdminAnalyticsCorrectionJob,
) {
    @Scheduled(cron = "\${buddystuddy.analytics.recent-cron:0 */5 * * * *}")
    fun refreshRecent() {
        jobs.execute(recentJob, JobTriggerType.SCHEDULED)
    }

    @Scheduled(cron = "\${buddystuddy.analytics.correction-cron:0 20 3 * * *}")
    fun refreshCorrection() {
        jobs.execute(correctionJob, JobTriggerType.SCHEDULED)
    }
}

@Component
class AdminAnalyticsRecentJob(
    private val analytics: AdminAnalyticsAggregationUseCase,
) : ManagedJob {
    override val name: String = "admin-analytics-recent"

    override fun run(): String {
        val today = LocalDate.now(ZoneOffset.UTC)
        return "rows=${analytics.refreshRecent(today)} referenceDate=$today"
    }
}

@Component
class AdminAnalyticsCorrectionJob(
    private val analytics: AdminAnalyticsAggregationUseCase,
) : ManagedJob {
    override val name: String = "admin-analytics-correction"

    override fun run(): String {
        val today = LocalDate.now(ZoneOffset.UTC)
        return "rows=${analytics.refreshCorrection(today)} referenceDate=$today"
    }
}
